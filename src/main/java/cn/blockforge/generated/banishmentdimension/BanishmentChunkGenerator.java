package cn.blockforge.generated.banishmentdimension;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;

/**
 * 放逐维度地形：Y=0 整层基岩地板，Y=1 一整层水源直接盖在基岩上（仅一层，浅水）。
 * 无结构、无矿物、无可刷生物。区块生成完全确定，加载极快。作者：VIASR。
 */
public class BanishmentChunkGenerator implements IChunkGenerator {

    private static final int BEDROCK_Y = 0;
    /** 水层：只一层，贴着基岩地板（Y=0 是基岩，Y=1 是水，人在基岩上踩水行走）。 */
    private static final int WATER_Y = 1;

    private final World world;

    public BanishmentChunkGenerator(World world) {
        this.world = world;
    }

    @Override
    public Chunk generateChunk(int x, int z) {
        Chunk chunk = new Chunk(world, x, z);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Chunk.setBlockState 的横坐标按 &15 取模，传区块内局部坐标即可
                chunk.setBlockState(new BlockPos(lx, BEDROCK_Y, lz), Blocks.BEDROCK.getDefaultState());
                chunk.setBlockState(new BlockPos(lx, WATER_Y, lz), Blocks.WATER.getDefaultState());
            }
        }
        byte[] biomes = chunk.getBiomeArray();
        for (int i = 0; i < biomes.length; i++) {
            biomes[i] = (byte) Biome.getIdForBiome(Biomes.PLAINS);
        }
        chunk.generateSkylightMap();
        chunk.setTerrainPopulated(true);
        chunk.setLightPopulated(true);
        chunk.resetRelightChecks();
        chunk.markDirty();
        return chunk;
    }

    /** 不生成任何东西：无装饰、无结构、无生物群系特征。 */
    @Override
    public void populate(int x, int z) {
        // void
    }

    @Override
    public boolean generateStructures(Chunk chunk, int x, int z) {
        return false;
    }

    /** 空刷怪列表：从源头禁止本维度任何自然刷怪。 */
    @Override
    public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos) {
        return new ArrayList<Biome.SpawnListEntry>();
    }

    @Override
    public BlockPos getNearestStructurePos(World worldIn, String structureName, BlockPos from, boolean findUnexplored) {
        return null;
    }

    @Override
    public void recreateStructures(Chunk chunk, int x, int z) {
        // void
    }

    @Override
    public boolean isInsideStructure(World worldIn, String structureName, BlockPos pos) {
        return false;
    }
}
