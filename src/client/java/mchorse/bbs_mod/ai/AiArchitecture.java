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

    public static class Result
    {
        public final String name;
        public final String title;
        public final int blocks;
        public final List<Integer> size;

        public Result(String name, String title, int blocks, List<Integer> size)
        {
            this.name = name;
            this.title = title;
            this.blocks = blocks;
            this.size = size;
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

            hollow(grid, from, to, stringOr(shell, "wall", wall), stringOr(shell, "floor", floor), stringOr(shell, "roof", roof));
            carveWindows(grid, from, to, windows);
            carveDoor(grid, from, to, door);
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

        int placed = grid.resolve();

        String rawName = stringOr(root, "name", "ai_build");
        String name = sanitize(rawName) + "_" + Long.toString(System.currentTimeMillis() % 100000L);
        String title = stringOr(root, "title", rawName);

        writeNbt(grid, new File(generatedDir, name + ".nbt"));

        if (schematicsDir != null)
        {
            schematicsDir.mkdirs();
            writeSchem(grid, new File(schematicsDir, name + ".schem"));
        }

        return new Result(name, title, placed, size);
    }

    /* ---- ops ---- */

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
        NbtIo.write(root, file);
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
        NbtIo.write(root, file);
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
