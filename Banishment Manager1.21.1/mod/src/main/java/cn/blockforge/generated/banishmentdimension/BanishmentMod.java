package cn.blockforge.generated.banishmentdimension;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 放逐 / Banishment · Fabric 1.21.1 重写版（r20）。原作者 VIASR，行为等价移植。
 *
 * 一句话设计：把玩家「放逐」到一个人造维度——基岩地板、一层浅水、永远明亮的灰色虚空，
 * 无怪、无结构、无法挖掘、无法饿死、无法自杀；管理员 /unbanish 之前出不去，
 * 但任何误入者都随时走得掉。放逐名单以纯文本 JSON 存在服务端存档的 banishment 文件夹，
 * 零 NBT、一个玩家一个文件、重启续放、可直接手改。
 *
 * 代码分七个类，各管一段，互不越界：
 *   BanishmentMod          本类，入口与总览注释
 *   BanishmentDimensions   维度注册键与落点常量
 *   ModConfig              配置文件读写与热更新（config/banishment.json，新结构）
 *   L10n                   中英文案取词（语言文件复用标准 lang JSON）
 *   BanishmentManager      放逐状态、背包内存对调、每刻巡检与状态循环
 *   BanishmentStorage      纯文本 JSON 落盘（r19 旧记录直接可读）
 *   BanishmentCommands     banish / unbanish / return / escape 四条命令
 *   BanishmentEvents       Fabric 事件接线（登录、登出、重生、tick、开关服）
 *   client BanishmentClient 客户端灰天空渲染器（装了本模组的客户端更沉浸）
 *
 * 维度地形与刷怪规则全部由 resources 里 data 下的数据包 JSON 声明（1.21.1 的
 * 正规做法），管理员可以整包拷走自行改造成「黑石牢房」「虚空基岩」等变体，
 * 对应配置 dimension.variant 三档。原版客户端也能正常游玩，只是天空显示为主世界
 * 样式（模组客户端才会替换成标志性的灰天）。
 */
public final class BanishmentMod implements ModInitializer {

    /** 模组 ID（沿用 r19，保证旧存档 banishment 文件夹与语言文件命名空间兼容）。 */
    public static final String MOD_ID = "banishment_dimension";
    /** 版本标识（写日志用）。 */
    public static final String VERSION = "1.0.0-r20";
    /** 统一日志器。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("Banishment");

    @Override
    public void onInitialize() {
        ModConfig.init();          // 先读配置：后面的取词与巡检参数都依赖它
        L10n.ensureLoaded();       // 预载中英文案
        BanishmentEvents.register();
        LOGGER.info("[放逐/Banishment] {} initialized · 维度变体 variant={} · 增强反馈 enhanced={}",
                VERSION, ModConfig.variant, ModConfig.enhancedFeedback);
    }
}
