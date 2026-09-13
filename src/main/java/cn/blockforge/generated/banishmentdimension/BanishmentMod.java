package cn.blockforge.generated.banishmentdimension;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.DimensionType;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

/**
 * 放逐 / Banishment v1.0.0-alpha  （作者：VIASR）
 *
 * 面向 1.12.2 Forge 服务器的非封禁轻度惩罚工具：
 * - /banish <玩家>   传送到放逐维度 114，冒险模式 + 空背包
 *                    （原版对象级换装，全程不做任何物品 NBT 序列化，兼容一切自定义物品模组）
 * - /banish list     查看当前被放逐玩家名单
 * - /banishtime <玩家> <分钟>   只对「已被 /banish 放逐」的玩家重设剩余时长（按在线分钟计时，
 *                    只在该玩家在线时倒计时，下线冻结并写进文件，到期自动解除）；
 *                    /banishtime add|set <玩家> <分钟> 增加/重设剩余时长，同样只对已放逐者生效
 * - /unbanish <玩家> 传回原位、恢复原游戏模式与原背包、清空放逐效果（离线玩家支持待解除）
 * - 放逐维度：基岩地板 + 海平面单层水 + 全亮灰天无昼夜，无生物无结构
 * - 10 秒循环刷新四大增益，防自杀防挖掘
 * - 维度切换即时事件拦截 + 每刻巡检双保险，防越狱、防死亡重生逃脱
 * - 玩家数据持久化：服务端存档根目录 banishment/ 文件夹，每玩家一个纯文本 JSON，
 *   零 NBT；关服/崩溃/重启后放逐照旧，背包快照按保存点回档，物品不蒸发
 * - 语言可配：config/banishment.cfg 里 S:language = zh / en / both
 *   （只中文 / 只英文 / 中英双语，默认双语），S:log_language 单独管后台日志；
 *   运行中改文件约 2 秒内热更新，不用重启服务器
 * - Forge 兼容：编译于 14.23.5.2847，经官方 jar 签名逐类核验，兼容 1.12.2 全部
 *   后续构建直至最终版 14.23.5.2864
 */
@Mod(modid = BanishmentMod.MOD_ID, name = "放逐 / Banishment", version = BanishmentMod.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        // 主界面 Mods → 选中「放逐 / Banishment」→ Config 这个配置页面（改语言用）。
        // 见 ModGuiFactory / BanishmentConfigGui：只在客户端被实例化，专用服务端不会加载，
        // 所以本模组依然不需要玩家安装任何东西。
        guiFactory = "cn.blockforge.generated.banishmentdimension.ModGuiFactory",
        // Forge 1.12.2 的模组 API 自 2811 起冻结：本模组用到的全部 24 个 Forge 类，
        // 在 14.23.5.2847 与 14.23.5.2864（1.12.2 最终构建）的官方 universal 包中
        // javap 签名逐一比对完全一致。这里显式声明兼容 2847 及以上的全部构建
        // （含 2864）；低于 2847 的旧构建会在启动时被 Forge 明确拦下，而不是带病运行。
        dependencies = "required-after:forge@[14.23.5.2847,)")
public final class BanishmentMod {

    public static final String MOD_ID = "banishment_dimension";
    public static final String VERSION = "1.0.0-r20";

    /** 放逐维度类型（preInit 注册后赋值；BanishmentProvider.getDimensionType() 需要引用它）。 */
    public static DimensionType banishmentType;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 读配置文件 config/banishment.cfg：聊天/日志语言在这里选（zh / en / both），
        // 不必改代码、不必换 jar；运行中改文件由 watchForExternalEdit() 约 2 秒内热更新。
        ModConfig.init(event.getSuggestedConfigurationFile());

        // 注册放逐维度（114）：类型 + 维度。keepSpawnLoaded=true 让 0,0 出生区块常驻，
        // 保证传送与死亡重生永远落在已加载的基岩地板上。
        banishmentType = DimensionType.register("banishment", "_banishment",
                BanishmentManager.DIM_ID, BanishmentProvider.class, true);
        DimensionManager.registerDimension(BanishmentManager.DIM_ID, banishmentType);

        // 事件：维度切换、登录、重生、刷怪拦截、每刻巡检（顺带巡检配置文件是否被改过）
        MinecraftForge.EVENT_BUS.register(new BanishmentEvents());
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBanish());
        event.registerServerCommand(new CommandUnbanish());
        event.registerServerCommand(new CommandReturn());
        event.registerServerCommand(new CommandEscape());
        event.registerServerCommand(new CommandBanishTime());
    }

    /**
     * 开服（世界已就绪）：从服务端存档根目录的 banishment/ 文件夹读回全部放逐记录，
     * 重启后续放：之前被放逐的玩家依然被放逐，原世界背包快照原样恢复。
     */
    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            BanishmentStorage.loadAll(server);
        }
        // 开服把当前生效的语言打印出来，方便确认配置文件读对了
        L10n.log("log.language", ModConfig.describe(ModConfig.language),
                ModConfig.CONFIG_ID + ".cfg");
    }

    /**
     * 关服收尾（持久化版）：不再自动解除放逐，而是把全部放逐记录（含原背包快照）
     * 落盘到存档的 banishment/ 文件夹，内存状态清空；下次开服自动续放。
     * 在线玩家留在 114 的部分由原版 player.dat 正常保存，物品零丢失。
     */
    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            BanishmentManager.persistAllOnStop(server);
        }
    }
}
