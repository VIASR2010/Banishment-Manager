package cn.blockforge.generated.banishmentdimension;

import java.util.List;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 放逐维度的注册键与常量。作者：VIASR，r20 数据驱动重写。
 *
 * r19（1.12.2 Forge）里维度是代码注册的整数 ID 114；1.21.1 的维度改由数据包 JSON
 * 声明（见 resources 下 data 里的 dimension 与 dimension_type），所以这里只拿注册键。
 * 三个变体（classic、dark、void）全部走数据文件定义地形与规则，配置里选用的那个即生效。
 * 想改地板结构、天空或刷怪规则，直接改数据包 JSON 或再加维度文件，不用动代码。
 */
public final class BanishmentDimensions {

    /** 经典：基岩地板 + 一层浅水 + 灰天（保留 r19 全部设计）。 */
    public static final RegistryKey<World> CLASSIC = key("banishment");
    /** 变体：黑石牢房（磨砂黑石地板，无浅水）。 */
    public static final RegistryKey<World> DARK = key("banishment_dark");
    /** 变体：虚空基岩（只有基岩地板）。 */
    public static final RegistryKey<World> VOID = key("banishment_void");

    /** 三个变体的注册键（客户端渲染器按这个列表逐个挂）。 */
    public static final List<RegistryKey<World>> ALL = List.of(CLASSIC, DARK, VOID);

    /**
     * 放逐维度安全落点：脚站 Y=1（基岩地板顶面；经典变体里脚踩基岩、身体泡在仅一层的浅水里，头露出水面）。
     * 三个变体共用同一个落点约定（min_y 都是 0、地板都只有一层）。
     */
    public static final double SAFE_X = 0.5D;
    public static final double SAFE_Y = 1.0D;
    public static final double SAFE_Z = 0.5D;
    public static final BlockPos SAFE_POS = new BlockPos(0, 1, 0);

    private BanishmentDimensions() {}

    /** 按模组命名空间拼一个维度注册键。 */
    public static RegistryKey<World> key(String path) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(BanishmentMod.MOD_ID, path));
    }

    /** 当前配置选用的变体维度。 */
    public static RegistryKey<World> active() {
        switch (ModConfig.variant) {
            case ModConfig.VARIANT_DARK: return DARK;
            case ModConfig.VARIANT_VOID: return VOID;
            default: return CLASSIC;
        }
    }

    /** 当前生效变体的已加载世界（数据文件缺失时为 null，调用方负责兜底）。 */
    public static ServerWorld activeWorld(MinecraftServer server) {
        return server == null ? null : server.getWorld(active());
    }

    /** 给消息用的维度显示名（相当于 r19 文案里的「114」位）。 */
    public static String display() {
        return active().getValue().getPath();
    }

    /** 某个维度键是否属于本模组的放逐维度（任意变体）。 */
    public static boolean isBanishment(RegistryKey<World> k) {
        return ALL.contains(k);
    }
}
