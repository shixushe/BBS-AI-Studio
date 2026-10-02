package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.BBSMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.PalettedContainer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Axiom 联动导出：把 AI 生成的方块网格写成 Axiom 自己的 .blueprint 文件
 * （反射调用 Axiom 的 BlueprintIo.writeRaw，避免编译期硬依赖），落盘到
 * config/axiom/blueprints/ —— Axiom 蓝图浏览器直接可见、可直接粘贴。
 *
 * <p>写入后立刻用 BlueprintIo.readRawBlueprint 读回自校验；Axiom 未加载或
 * 格式不匹配时抛异常，上层回退到始终会写的 .schem/.nbt。</p>
 *
 * <p>cells 的键打包格式为 (x &lt;&lt; 24 | z &lt;&lt; 12 | y)，来自 AiArchitecture。</p>
 */
public class AxiomBlueprintWriter
{
    private static Boolean axiomLoaded;

    public static boolean isAxiomLoaded()
    {
        if (axiomLoaded == null)
        {
            axiomLoaded = FabricLoader.getInstance().isModLoaded("axiom");
        }

        return axiomLoaded;
    }

    public static File blueprintDir()
    {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "axiom/blueprints");

        dir.mkdirs();

        return dir;
    }

    /**
     * @param cells packed (x << 24 | z << 12 | y) -> block id, from AiArchitecture
     * @return the written .blueprint file
     */
    public static File export(File dir, String name, String title, Map<Long, String> cells, int[] size) throws Exception
    {
        dir.mkdirs();

        Class<?> ioClass = Class.forName("com.moulberry.axiom.blueprint.BlueprintIo");
        Class<?> headerClass = Class.forName("com.moulberry.axiom.blueprint.BlueprintHeader");
        Class<?> rawClass = Class.forName("com.moulberry.axiom.blueprint.RawBlueprint");

        /* Header record: (name, author, tags, yaw, pitch, lockedThumbnail, blockCount, containsAir) */
        Object header = null;

        for (Constructor<?> ctor : headerClass.getConstructors())
        {
            Class<?>[] types = ctor.getParameterTypes();

            if (types.length == 8 && types[0] == String.class && types[1] == String.class)
            {
                header = ctor.newInstance(name, "BBS AI Studio", new ArrayList<>(List.of("ai")), 45F, 30F, false, cells.size(), false);

                break;
            }
        }

        if (header == null)
        {
            throw new IllegalStateException("BlueprintHeader constructor not found");
        }

        /* Blocks: one 16³ PalettedContainer section per chunk section, keyed by
         * BlockPos.asLong(sectionX, sectionY, sectionZ) */
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        int sectionsX = (size[0] + 15) / 16;
        int sectionsY = (size[1] + 15) / 16;
        int sectionsZ = (size[2] + 15) / 16;

        for (int sy = 0; sy < sectionsY; sy++)
        {
            for (int sz = 0; sz < sectionsZ; sz++)
            {
                for (int sx = 0; sx < sectionsX; sx++)
                {
                    sections.put(BlockPos.asLong(sx * 16, sy * 16, sz * 16), new PalettedContainer<>(
                        Block.STATE_IDS, Blocks.AIR.getDefaultState(),
                        PalettedContainer.PaletteProvider.BLOCK_STATE));
                }
            }
        }

        for (Map.Entry<Long, String> entry : cells.entrySet())
        {
            long key = entry.getKey();
            int x = (int) (key >> 24) & 0xFFF;
            int z = (int) (key >> 12) & 0xFFF;
            int y = (int) (key & 0xFFF);

            PalettedContainer<BlockState> section = sections.get(BlockPos.asLong((x / 16) * 16, (y / 16) * 16, (z / 16) * 16));

            if (section == null)
            {
                continue;
            }

            BlockState state = blockState(entry.getValue());

            if (state != null)
            {
                section.swap(x & 15, y & 15, z & 15, state);
            }
        }

        /* RawBlueprint record: (header, thumbnail, blocks, blockEntities) */
        Constructor<?> rawCtor = null;

        for (Constructor<?> ctor : rawClass.getDeclaredConstructors())
        {
            Class<?>[] types = ctor.getParameterTypes();

            if (types.length >= 3 && types.length <= 6 && types[0] == headerClass)
            {
                rawCtor = ctor;
                rawCtor.setAccessible(true);

                break;
            }
        }

        if (rawCtor == null)
        {
            StringBuilder signatures = new StringBuilder();

            for (Constructor<?> ctor : rawClass.getDeclaredConstructors())
            {
                signatures.append(ctor.toString()).append("; ");
            }

            throw new IllegalStateException("RawBlueprint constructor not found, declared: " + signatures);
        }

        /* Adaptive arguments: header first, then fill every parameter by type
         * (thumbnail byte[], the sections/blockEntities Long2ObjectMaps in
         * order, ints/longs 0, booleans false, anything else null) */
        Class<?>[] types = rawCtor.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean blocksGiven = false;

        for (int i = 0; i < types.length; i++)
        {
            Class<?> type = types[i];

            if (i == 0)
            {
                args[i] = header;
            }
            else if (type == byte[].class)
            {
                args[i] = new byte[0];
            }
            else if (type.getSimpleName().contains("Long2ObjectMap"))
            {
                args[i] = blocksGiven ? new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>() : sections;
                blocksGiven = true;
            }
            else if (type == int.class)
            {
                args[i] = 0;
            }
            else if (type == long.class)
            {
                args[i] = 0L;
            }
            else if (type == boolean.class)
            {
                args[i] = false;
            }
            else if (type == float.class)
            {
                args[i] = 0F;
            }
            else
            {
                args[i] = null;
            }
        }

        Object raw = rawCtor.newInstance(args);

        Method writeRaw = ioClass.getMethod("writeRaw", java.io.OutputStream.class, rawClass);

        File out = new File(dir, name + ".blueprint");

        try (FileOutputStream stream = new FileOutputStream(out))
        {
            writeRaw.invoke(null, stream, raw);
        }

        /* Round-trip self-check: Axiom's own reader must accept our file */
        Method readRaw = ioClass.getMethod("readRawBlueprint", java.io.InputStream.class);

        try (java.io.FileInputStream in = new java.io.FileInputStream(out))
        {
            Object readBack = readRaw.invoke(null, in);

            if (readBack == null)
            {
                throw new IllegalStateException("read-back returned null");
            }
        }

        return out;
    }

    private static BlockState blockState(String id)
    {
        String full = id.contains(":") ? id : "minecraft:" + id;

        try
        {
            return net.minecraft.registry.Registries.BLOCK.get(new net.minecraft.util.Identifier(full)).getDefaultState();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
