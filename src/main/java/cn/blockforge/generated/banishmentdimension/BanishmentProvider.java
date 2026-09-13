package cn.blockforge.generated.banishmentdimension;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;

/**
 * 放逐维度环境：全亮、灰色无昼夜天空、无云无天气无星空，
 * 禁止睡眠、禁止刷怪、固定出生点在 (0,1,0) 基岩地板上。作者：VIASR。
 *
 * Forge 1.12.2（编译于 14.23.5.2847，运行兼容至 14.23.5.2864）直接用
 * WorldProvider 子类注册维度（DimensionType.register 第五个参数即本类 class），
 * 无需旧的 IDimensionProvider。
 */
public class BanishmentProvider extends WorldProvider {

    /** 灰天/灰雾颜色（一个色调，配合全亮光照表 → 恒定灰色空间） */
    private static final double SKY_GRAY = 0.34D;

    @Override
    public DimensionType getDimensionType() {
        return BanishmentMod.banishmentType;
    }

    @Override
    protected void init() {
        this.hasSkyLight = true;
        this.doesWaterVaporize = false;
        this.nether = false;
        if (this.biomeProvider == null && this.world != null) {
            this.biomeProvider = new BiomeProvider(this.world.getWorldInfo());
        }
        // 不允许和平/敌对自然刷怪（与刷怪事件拦截、空刷怪列表组成三重保险）
        setAllowedSpawnTypes(false, false);
    }

    /** 光照表全拉满：放逐维度永远通亮，不存在"黑到能刷怪"的夜晚。 */
    @Override
    protected void generateLightBrightnessTable() {
        for (int i = 0; i < this.lightBrightnessTable.length; i++) {
            this.lightBrightnessTable[i] = 1.0F;
        }
    }

    @Override
    public IChunkGenerator createChunkGenerator() {
        return new BanishmentChunkGenerator(this.world);
    }

    @Override
    public boolean canRespawnHere() {
        return true;
    }

    // ---- 天空与天气：灰色、无昼夜 ----

    @Override
    public boolean isSurfaceWorld() {
        return false;
    }

    @Override
    public boolean isSkyColored() {
        return false;
    }

    @Override
    public float calculateCelestialAngle(long worldTime, float partialTicks) {
        return 0.5F;
    }

    @Override
    public int getMoonPhase(long worldTime) {
        return 0;
    }

    @Override
    public float[] calcSunriseSunsetColors(float celestialAngle, float partialTicks) {
        return new float[4];
    }

    @Override
    public Vec3d getFogColor(float celestialAngle, float partialTicks) {
        return new Vec3d(SKY_GRAY, SKY_GRAY, SKY_GRAY);
    }

    @Override
    public Vec3d getSkyColor(Entity cameraEntity, float partialTicks) {
        return new Vec3d(SKY_GRAY, SKY_GRAY, SKY_GRAY);
    }

    @Override
    public float getCloudHeight() {
        return -999.0F;
    }

    @Override
    public float getStarBrightness(float partialTicks) {
        return 0.0F;
    }

    @Override
    public float getSunBrightness(float partialTicks) {
        return 0.0F;
    }

    @Override
    public float getSunBrightnessFactor(float partialTicks) {
        return 0.0F;
    }

    @Override
    public float getCurrentMoonPhaseFactor() {
        return 0.0F;
    }

    @Override
    public boolean isDaytime() {
        return true;
    }

    @Override
    public boolean doesXZShowFog(int x, int z) {
        return false;
    }

    @Override
    public double getVoidFogYFactor() {
        return 1.0D;
    }

    @Override
    public boolean canDoLightning(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canDoRainSnowIce(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canBlockFreeze(BlockPos pos, boolean byWater) {
        return false;
    }

    @Override
    public boolean canSnowAt(BlockPos pos, boolean checkLight) {
        return false;
    }

    // ---- 出生点 / 存档 / 规则 ----

    @Override
    public String getSaveFolder() {
        return "DIM" + BanishmentManager.DIM_ID;
    }

    @Override
    public BlockPos getSpawnCoordinate() {
        return BanishmentManager.SAFE_POS;
    }

    @Override
    public BlockPos getRandomizedSpawnPoint() {
        return BanishmentManager.SAFE_POS;
    }

    @Override
    public int getAverageGroundLevel() {
        return 1;
    }

    @Override
    public double getHorizon() {
        return 0.0D;
    }

    @Override
    public WorldSleepResult canSleepAt(EntityPlayer player, BlockPos pos) {
        return WorldSleepResult.DENY;
    }

    /** 死亡重生的兜底：被放逐玩家死了也只能回到 114 的出生点。 */
    @Override
    public int getRespawnDimension(EntityPlayerMP player) {
        return BanishmentManager.isBanished(player) ? BanishmentManager.DIM_ID : 0;
    }
}
