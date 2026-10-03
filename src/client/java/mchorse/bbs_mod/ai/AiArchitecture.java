package mchorse.bbs_mod.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mchorse.bbs_mod.utils.IOUtils;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtShort;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.SharedConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 建筑：LLM 输出一份<strong>严格 JSON 的建造单</strong>，本类把它确定性地
 * 展开成方块网格并落盘成两种蓝图——
 *
 * <ul>
 * <li>原版 .nbt 结构（写入 存档 generated/bbs/structures/，AI 结构面板与
 *     放置通道直接可用）</li>
 * <li>Sponge v2 .schem（写入 创世神 schematics 文件夹，//schem load 可加载，
 *     Axiom 等模组亦认这一格式）</li>
 * </ul>
 *
 * <p>确定性是刻意的：LLM 只决定尺寸/材料/形态组合（boxes 实心填充、
 * hollow_box 带墙楼体+窗+门、towers 带雉堞塔），坐标全部 clamp 在尺寸内、
 * 未知方块静默剔除——模型再怎么幻觉也产不出坏结构。</p>
 */
public class AiArchitecture
{
    /** Set by the structure panel; the server's placement answer lands here. */
    public static java.util.function.Consumer<String> onPlaced;

    /** The last generated structure's registry path (bbs namespace). */
    public static String lastGenerated;

    /** The last generation's full result (cells + size) for re-export. */
    public static Result lastResult;

    /** The Axiom blueprint file written for the last generation, if Axiom is loaded. */
    public static File lastAxiom;

    public static class Result
    {
        public final String name;
        public final String title;
        public final int blocks;
        public final List<Integer> size;

        /** Resolved block id per packed (x << 24 | z << 12 | y) cell. */
        public final Map<Long, String> cells;

        public Result(String name, String title, int blocks, List<Integer> size, Map<Long, String> cells)
        {
            this.name = name;
            this.title = title;
            this.blocks = blocks;
            this.size = size;
            this.cells = cells;
        }
    }

    /** Parse the LLM reply, expand it, and write both blueprint files. */
    public static Result generate(String raw, File generatedDir, File schematicsDir) throws Exception
    {
        int braceStart = raw.indexOf('{');
        int braceEnd = raw.lastIndexOf('}');

        if (braceStart < 0 || braceEnd <= braceStart)
        {
            throw new IllegalArgumentException("no JSON");
        }

        JsonObject root = JsonParser.parseString(raw.substring(braceStart, braceEnd + 1)).getAsJsonObject();

        List<Integer> size = size(root);
        Grid grid = new Grid(size.get(0), size.get(1), size.get(2));

        String wall = stringOr(root, "wall", "minecraft:stone_bricks");
        String floor = stringOr(root, "floor", "minecraft:stone");
        String roof = stringOr(root, "roof", "minecraft:dark_oak_slab");

        /* The shell: a hollow building with floor/walls/roof, windows and a door */
        if (root.has("shell"))
        {
            JsonObject shell = root.getAsJsonObject("shell");
            int[] from = point(shell, "from");
            int[] to = point(shell, "to");
            boolean windows = boolOr(shell, "windows", true);
            String door = stringOr(shell, "door", "south");
            String wallBlock = stringOr(shell, "wall", wall);
            String roofBlock = stringOr(shell, "roof", roof);
            String roofStyle = stringOr(shell, "roof_style", "flat");

            hollow(grid, from, to, wallBlock, stringOr(shell, "floor", floor), roofStyle.equals("flat") ? roofBlock : null);
            carveWindows(grid, from, to, windows);
            carveDoor(grid, from, to, door);

            /* 立柱：四角通高（原木类，视觉骨架） */
            if (root.has("pillars") || shell.has("pillars"))
            {
                JsonObject pillars = root.has("pillars") ? root.getAsJsonObject("pillars") : shell.getAsJsonObject("pillars");
                String pillarBlock = stringOr(pillars, "block", "minecraft:oak_log");

                for (int[] corner : new int[][] { {from[0], from[2]}, {to[0], from[2]}, {from[0], to[2]}, {to[0], to[2]} })
                {
                    fill(grid, new int[] {corner[0], from[1] + 1, corner[1]}, new int[] {corner[0], to[1] - 1, corner[1]}, pillarBlock);
                }
            }

            /* 屋顶风格：stepped 阶梯实心 / gable 人字（脊沿 X）/ flat 平顶（默认在 hollow 内） */
            if (roofStyle.equals("stepped"))
            {
                steppedRoof(grid, from, to, roofBlock, false);
            }
            else if (roofStyle.equals("gable"))
            {
                gableRoof(grid, from, to, roofBlock);
            }

            /* 窗阵：每层每 spacing 开一对玻璃窗（对称） */
            if (shell.has("windows_grid"))
            {
                JsonObject gridSpec = shell.getAsJsonObject("windows_grid");
                int spacing = Math.max(2, gridSpec.get("spacing") == null ? 3 : gridSpec.get("spacing").getAsInt());
                String pane = stringOr(gridSpec, "pane", "minecraft:glass_pane");
                int floors = Math.max(1, gridSpec.get("floors") == null ? 1 : gridSpec.get("floors").getAsInt());

                windowGrid(grid, from, to, spacing, pane, floors);
            }

            /* 楼层板：多层中空楼的层间地板 */
            if (shell.has("floors"))
            {
                JsonObject floorsSpec = shell.getAsJsonObject("floors");
                int count = Math.max(1, floorsSpec.get("count") == null ? 1 : floorsSpec.get("count").getAsInt());
                String slab = stringOr(floorsSpec, "block", stringOr(shell, "floor", floor));
                int span = (to[1] - from[1] - 1) / (count + 1);

                if (span > 0)
                {
                    for (int f = 1; f <= count; f++)
                    {
                        int y = from[1] + span * f;

                        fill(grid, new int[] {from[0], y, from[2]}, new int[] {to[0], y, to[2]}, slab);
                        carveDoor(grid, from, to, door);
                    }
                }
            }
        }

        /* Solid fills: floors, platforms, terrain pads */
        for (JsonElement e : list(root, "boxes"))
        {
            JsonObject box = e.getAsJsonObject();
            int[] from = point(box, "from");
            int[] to = point(box, "to");
            String block = stringOr(box, "block", floor);

            fill(grid, from, to, block);
        }

        /* Towers: hollow perimeter columns with an optional crenellated cap */
        for (JsonElement e : list(root, "towers"))
        {
            JsonObject tower = e.getAsJsonObject();
            int[] from = point(tower, "from");
            int[] to = point(tower, "to");
            String block = stringOr(tower, "block", wall);

            hollow(grid, from, to, block, block, boolOr(tower, "roof", true) ? block : null);
        }

        /* 自由细节命令（BuilderGPT 风格）：LLM 直接给 fill/setblock，坐标全部
         * clamp 在尺寸内、非法方块由 resolve 静默剔除 */
        for (JsonElement e : list(root, "commands"))
        {
            if (!e.isJsonObject())
            {
                continue;
            }

            JsonObject cmd = e.getAsJsonObject();
            String op = stringOr(cmd, "type", "setblock");
            String block = stringOr(cmd, "block", "");

            if (block.isEmpty())
            {
                continue;
            }

            if (op.equals("fill") && cmd.has("from") && cmd.has("to"))
            {
                fill(grid, point(cmd, "from"), point(cmd, "to"), block);
            }
            else
            {
                int x = cmd.has("x") ? cmd.get("x").getAsInt() : 0;
                int y = cmd.has("y") ? cmd.get("y").getAsInt() : 0;
                int z = cmd.has("z") ? cmd.get("z").getAsInt() : 0;

                grid.put(x, y, z, block);
            }
        }

        int placed = grid.resolve();

        String rawName = stringOr(root, "name", "ai_build");
        String name = sanitize(rawName) + "_" + Long.toString(System.currentTimeMillis() % 100000L);
        String title = stringOr(root, "title", rawName);

        writeNbt(grid, new File(generatedDir, name + ".nbt"));

        if (schematicsDir != null)
        {
            schematicsDir.mkdirs();
            writeSchem(grid, new File(schematicsDir, name + ".schem"));
            writeMcfunction(grid, new File(schematicsDir, name + ".mcfunction"));
        }

        Result result = new Result(name, title, placed, size, new LinkedHashMap<>(grid.cells()));

        this_or_static(result, generatedDir, schematicsDir);

        return result;
    }

    /* ---- ops ---- */

    /** 阶梯实心屋顶：逐层向内收缩（四向），roof 材质铺面。 */
    private static void steppedRoof(Grid grid, int[] from, int[] to, String roof, boolean pyramidOnly)
    {
        int layers = Math.min((to[0] - from[0]) / 2, (to[2] - from[2]) / 2);

        for (int i = 1; i <= layers; i++)
        {
            int[] f = {from[0] + i, to[1] + i, from[2] + i};
            int[] t = {to[0] - i, to[1] + i, to[2] - i};

            if (f[0] > t[0] || f[2] > t[2])
            {
                break;
            }

            fill(grid, f, t, roof);
        }
    }

    /** 人字屋顶：脊沿 X，两侧逐层向内（Z 向收缩），roof 材质。 */
    private static void gableRoof(Grid grid, int[] from, int[] to, String roof)
    {
        int layers = (to[2] - from[2]) / 2;

        for (int i = 1; i <= layers; i++)
        {
            int[] f = {from[0], to[1] + i, from[2] + i};
            int[] t = {to[0], to[1] + i, to[2] - i};

            if (f[2] > t[2])
            {
                break;
            }

            fill(grid, f, t, roof);
        }
    }

    /** 对称窗阵：每层沿四墙每 spacing 开窗（跳过门位那一格由 carveDoor 之后覆盖）。 */
    private static void windowGrid(Grid grid, int[] from, int[] to, int spacing, String pane, int floors)
    {
        int wallTop = to[1] - 1;
        int span = to[2] - from[2];
        int spanX = to[0] - from[0];
        int floorH = Math.max(2, (span - 2) / Math.max(1, floors));
        int midZ = from[2] + span / 2;
        int midX = from[0] + spanX / 2;

        for (int f = 0; f < floors; f++)
        {
            int y = from[1] + 2 + f * floorH;

            if (y > wallTop - 1)
            {
                break;
            }

            for (int x = from[0] + 2; x < to[0] - 1; x += spacing)
            {
                grid.put(x, y, from[2], pane);
                grid.put(x, y, to[2], pane);
            }

            for (int z = from[2] + 2; z < to[2] - 1; z += spacing)
            {
                grid.put(from[0], y, z, pane);
                grid.put(to[0], y, z, pane);
            }
        }
    }

    private static void fill(Grid grid, int[] from, int[] to, String block)
    {
        for (int x = from[0]; x <= to[0]; x++)
        {
            for (int y = from[1]; y <= to[1]; y++)
            {
                for (int z = from[2]; z <= to[2]; z++)
                {
                    grid.put(x, y, z, block);
                }
            }
        }
    }

    private static void hollow(Grid grid, int[] from, int[] to, String wall, String floor, String roof)
    {
        for (int x = from[0]; x <= to[0]; x++)
        {
            for (int z = from[2]; z <= to[2]; z++)
            {
                boolean edge = x == from[0] || x == to[0] || z == from[2] || z == to[2];

                for (int y = from[1]; y <= to[1]; y++)
                {
                    if (edge)
                    {
                        grid.put(x, y, z, wall);
                    }
                    else if (y == from[1] && floor != null)
                    {
                        grid.put(x, y, z, floor);
                    }
                    else if (y == to[1] && roof != null)
                    {
                        grid.put(x, y, z, roof);
                    }
                }
            }
        }
    }

    private static void carveWindows(Grid grid, int[] from, int[] to, boolean windows)
    {
        if (!windows)
        {
            return;
        }

        int midX = (from[0] + to[0]) / 2;
        int midZ = (from[2] + to[2]) / 2;

        for (int y = from[1] + 2; y <= to[1] - 1; y += 3)
        {
            grid.put(midX, y, from[2], "minecraft:glass");
            grid.put(midX, y, to[2], "minecraft:glass");
            grid.put(from[0], y, midZ, "minecraft:glass");
            grid.put(to[0], y, midZ, "minecraft:glass");
        }
    }

    private static void carveDoor(Grid grid, int[] from, int[] to, String door)
    {
        int midX = (from[0] + to[0]) / 2;
        int midZ = (from[2] + to[2]) / 2;

        for (int y = from[1] + 1; y <= from[1] + 2 && y <= to[1]; y++)
        {
            switch (door == null ? "" : door)
            {
                case "north": grid.put(midX, y, from[2], "minecraft:air"); break;
                case "south": grid.put(midX, y, to[2], "minecraft:air"); break;
                case "west": grid.put(from[0], y, midZ, "minecraft:air"); break;
                case "east": grid.put(to[0], y, midZ, "minecraft:air"); break;
            }
        }
    }

    /* ---- grid ---- */

    private static class Grid
    {
        private final int[] size;
        private final Map<Long, String> cells = new LinkedHashMap<>();
        private int unknown;

        Grid(int w, int h, int d)
        {
            this.size = new int[]{w, h, d};
        }

        void put(int x, int y, int z, String block)
        {
            if (x < 0 || y < 0 || z < 0 || x >= this.size[0] || y >= this.size[1] || z >= this.size[2])
            {
                return;
            }

            this.cells.put(key(x, y, z), block);
        }

        private static long key(int x, int y, int z)
        {
            return ((long) x << 24) | ((long) z << 12) | y;
        }

        /** Resolve ids against the registry; unknown ones drop out. */
        int resolve()
        {
            Map<Long, String> resolved = new LinkedHashMap<>();

            for (Map.Entry<Long, String> entry : this.cells.entrySet())
            {
                String id = entry.getValue();

                if (!id.contains(":"))
                {
                    id = "minecraft:" + id;
                }

                try
                {
                    Block block = Registries.BLOCK.get(new Identifier(id));

                    if (block != null && block.getDefaultState() != null && !id.endsWith("air"))
                    {
                        resolved.put(entry.getKey(), id);
                    }
                    else if (id.endsWith(":air"))
                    {
                        resolved.put(entry.getKey(), "minecraft:air");
                    }
                    else
                    {
                        this.unknown++;
                    }
                }
                catch (Exception e)
                {
                    this.unknown++;
                }
            }

            this.cells.clear();
            this.cells.putAll(resolved);

            return this.cells.size();
        }

        int unknown()
        {
            return this.unknown;
        }

        Map<Long, String> cells()
        {
            return this.cells;
        }

        int[] size()
        {
            return this.size;
        }
    }

    /* ---- writers ---- */

    /**
     * BuilderGPT 式 mcfunction 导出：每格一条 setblock（相对原点），可直接丢进
     * 数据包函数或用 /function 调用——WorldEdit 之外的第二条导入路径。
     */
    private static void writeMcfunction(Grid grid, File file) throws Exception
    {
        file.getParentFile().mkdirs();

        StringBuilder out = new StringBuilder("# generated by BBS AI Studio\n");

        for (Map.Entry<Long, String> entry : grid.cells().entrySet())
        {
            long packed = entry.getKey();
            int x = (int) (packed >> 24);
            int z = (int) ((packed >> 12) & 0xFFF);
            int y = (int) (packed & 0xFFF);

            out.append("setblock ~").append(x >= 0 ? "" + x : "~" + x)
                .append(" ~").append(y >= 0 ? "" + y : "~" + y)
                .append(" ~").append(z >= 0 ? "" + z : "~" + z)
                .append(" ").append(entry.getValue())
                .append("\n");
        }

        java.nio.file.Files.writeString(file.toPath(), out.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Vanilla structure NBT, into the save's generated/bbs/structures. */
    private static void writeNbt(Grid grid, File file) throws Exception
    {
        Map<String, Integer> palette = new LinkedHashMap<>();
        NbtList blocks = new NbtList();

        for (Map.Entry<Long, String> entry : grid.cells().entrySet())
        {
            long key = entry.getKey();
            int x = (int) (key >> 24) & 0xFFF;
            int z = (int) (key >> 12) & 0xFFF;
            int y = (int) (key & 0xFFF);

            Integer index = palette.computeIfAbsent(entry.getValue(), (id) -> palette.size());

            NbtCompound blockEntry = new NbtCompound();
            NbtList pos = new NbtList();

            pos.add(NbtInt.of(x));
            pos.add(NbtInt.of(y));
            pos.add(NbtInt.of(z));

            blockEntry.put("pos", pos);
            blockEntry.putInt("state", index);
            blocks.add(blockEntry);
        }

        NbtList paletteList = new NbtList();

        for (String id : palette.keySet())
        {
            NbtCompound entry = new NbtCompound();

            entry.putString("Name", id);
            paletteList.add(entry);
        }

        NbtCompound root = new NbtCompound();
        NbtList size = new NbtList();

        for (int v : grid.size())
        {
            size.add(NbtInt.of(v));
        }

        root.put("size", size);
        root.put("blocks", blocks);
        root.put("palette", paletteList);
        root.put("entities", new NbtList());
        root.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());

        file.getParentFile().mkdirs();
        /* 原版结构块与 StructureManager 都按 gzip 压缩读写——裸 NBT 会被读取端
         * 当作损坏文件拒收 */
        NbtIo.writeCompressed(root, file);
    }

    /** Sponge schematic v2, into WorldEdit's schematics folder. */
    private static void writeSchem(Grid grid, File file) throws Exception
    {
        int[] size = grid.size();
        Map<String, Integer> palette = new LinkedHashMap<>();
        List<Integer> data = new ArrayList<>();

        /* Sponge order: y (bottom-up), then z, then x */
        for (int y = 0; y < size[1]; y++)
        {
            for (int z = 0; z < size[2]; z++)
            {
                for (int x = 0; x < size[0]; x++)
                {
                    String block = grid.cells().getOrDefault(Grid.key(x, y, z), "minecraft:air");
                    int index = palette.computeIfAbsent(block, (id) -> palette.size());

                    data.add(index);
                }
            }
        }

        java.io.ByteArrayOutputStream varint = new java.io.ByteArrayOutputStream();

        for (int value : data)
        {
            while ((value & ~0x7F) != 0)
            {
                varint.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }

            varint.write(value);
        }

        NbtCompound root = new NbtCompound();

        root.putShort("Width", (short) size[0]);
        root.putShort("Height", (short) size[1]);
        root.putShort("Length", (short) size[2]);
        root.putInt("PaletteMax", palette.size());
        root.putInt("Version", 2);
        root.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());

        NbtCompound paletteNbt = new NbtCompound();

        for (Map.Entry<String, Integer> entry : palette.entrySet())
        {
            paletteNbt.putInt(entry.getKey(), entry.getValue());
        }

        root.put("Palette", paletteNbt);
        root.put("BlockData", new NbtByteArray(varint.toByteArray()));

        NbtCompound metadata = new NbtCompound();

        metadata.putString("Name", "BBS AI architecture");
        root.put("Metadata", metadata);

        file.getParentFile().mkdirs();
        /* 原版结构块与 StructureManager 都按 gzip 压缩读写——裸 NBT 会被读取端
         * 当作损坏文件拒收 */
        NbtIo.writeCompressed(root, file);
    }

    /** Remember the result and, when Axiom is loaded, write its native blueprint. */
    private static void this_or_static(Result result, File generatedDir, File schematicsDir)
    {
        lastResult = result;
        lastAxiom = null;

        try
        {
            if (AxiomBlueprintWriter.isAxiomLoaded())
            {
                lastAxiom = AxiomBlueprintWriter.export(AxiomBlueprintWriter.blueprintDir(),
                    result.name, result.title, result.cells,
                    new int[]{result.size.get(0), result.size.get(1), result.size.get(2)});
            }
        }
        catch (Exception e)
        {
            /* The .schem/.nbt blueprints are already written; the Axiom-native
             * one is a bonus - a failure here must not fail the generation */
            e.printStackTrace();
        }
    }

    /* ---- json helpers ---- */

    private static List<Integer> size(JsonObject root)
    {
        List<Integer> out = new ArrayList<>();

        if (root.has("size") && root.get("size").isJsonArray())
        {
            JsonArray array = root.getAsJsonArray("size");

            for (int i = 0; i < 3 && i < array.size(); i++)
            {
                out.add(Math.max(3, Math.min(48, array.get(i).getAsInt())));
            }
        }

        while (out.size() < 3)
        {
            out.add(9);
        }

        return out;
    }

    private static int[] point(JsonObject object, String key)
    {
        int[] out = {0, 0, 0};

        if (object.has(key) && object.get(key).isJsonArray())
        {
            JsonArray array = object.getAsJsonArray(key);

            for (int i = 0; i < 3 && i < array.size(); i++)
            {
                out[i] = array.get(i).getAsInt();
            }
        }

        return out;
    }

    private static List<JsonElement> list(JsonObject root, String key)
    {
        if (!root.has(key) || !root.get(key).isJsonArray())
        {
            return List.of();
        }

        List<JsonElement> out = new ArrayList<>();
        JsonArray array = root.getAsJsonArray(key);

        for (JsonElement element : array)
        {
            if (element.isJsonObject())
            {
                out.add(element);
            }
        }

        return out;
    }

    private static String stringOr(JsonObject object, String key, String fallback)
    {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static boolean boolOr(JsonObject object, String key, boolean fallback)
    {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static String sanitize(String name)
    {
        String slug = name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9_]+", "_");

        return slug.isEmpty() || slug.equals("_") ? "ai_build" : slug.substring(0, Math.min(24, slug.length()));
    }
}
