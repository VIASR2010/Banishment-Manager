package cn.blockforge.generated.banishmentdimension;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * 模组配置文件：config/banishment.cfg （作者：VIASR）
 *
 * 想改聊天语言，只改这里就够了，不用动代码也不用换 jar。服务器启动后 Forge 会自动
 * 在 config/ 目录下生成 banishment.cfg，用记事本打开就能看到：
 *
 *   # 语言 / Language
 *   S:language=both
 *
 * S:language 三种取值（大小写随意，中英文别名也认）：
 *   zh / zh_cn / chinese / cn   →  只发中文
 *   en / en_us / english        →  只发英文
 *   both / bilingual            →  中英双语同时发（默认，中文一行、英文一行）
 * S:log_language 单独控制后台控制台与日志文件的输出语言，取值同上，默认 both。
 *
 * 生效时机：
 *   1) 开服时读一次；
 *   2) 服务器运行中直接改文件存盘，最多 2 秒内自动热更新（不必重启，也不必再进存档）；
 *   3) 在游戏里「模组配置」界面改完关掉页面，立刻生效。
 * 写错值不会崩服：会自动按双语处理并在控制台留一行提示。
 */
public final class ModConfig {

    /** 只中文。 */
    public static final String ZH = "zh";
    /** 只英文。 */
    public static final String EN = "en";
    /** 中英双语（默认）。 */
    public static final String BOTH = "both";

    /** 配置项允许的取值（写进 .cfg 后，游戏里的配置界面会把它做成下拉框）。 */
    private static final String[] OPTIONS = new String[] { ZH, EN, BOTH };

    /** 聊天消息语言（当前生效值）。 */
    public static volatile String language = BOTH;
    /** 控制台日志语言（当前生效值）。 */
    public static volatile String logLanguage = BOTH;

    /** 文件名：config/banishment.cfg */
    public static final String CONFIG_ID = "banishment";

    private static Configuration config;
    private static Property propLanguage;
    private static Property propLogLanguage;

    /** 上次读到的文件时间戳，用来发现「管理员用记事本改过了」。 */
    private static long lastModified = -1L;
    /** 热重载的最小间隔（毫秒），不必每 tick 都问文件系统。 */
    private static final long POLL_INTERVAL_MS = 2000L;
    private static long lastPollAt = 0L;

    private ModConfig() {}

    /** preInit 调用：建文件、补齐缺项、把当前值读进内存。 */
    public static void init(File suggestedFile) {
        File file = new File(suggestedFile.getParentFile(), CONFIG_ID + ".cfg");
        config = new Configuration(file);
        config.load();

        config.addCustomCategoryComment(Configuration.CATEGORY_GENERAL,
                "放逐 / Banishment 的全部可调项\n"
              + "本文件可用记事本直接编辑，服务器运行中改完存盘约 2 秒内自动生效。\n"
              + "Edit with any text editor; changes are picked up live (about 2s). Wrong values fall back to defaults.");

        propLanguage = config.get(Configuration.CATEGORY_GENERAL, "language", BOTH,
                "聊天消息语言 / chat message language\n"
              + "zh = 只中文 (Chinese only)\n"
              + "en = 只英文 (English only)\n"
              + "both = 中英双语，中文一行英文一行 (bilingual, default)\n"
              + "别名也认：zh_cn / chinese / cn / en_us / english / bilingual",
                OPTIONS);
        propLanguage.setLanguageKey("config.banishment.language");
        propLanguage.setRequiresWorldRestart(false);

        propLogLanguage = config.get(Configuration.CATEGORY_GENERAL, "log_language", BOTH,
                "后台控制台与日志文件的语言 / console & log file language\n"
              + "zh = 只中文 · en = 只英文 · both = 中英双语（默认）\n"
              + "只影响服务器后台打印，不影响玩家看到的聊天。",
                OPTIONS);
        propLogLanguage.setLanguageKey("config.banishment.log_language");
        propLogLanguage.setRequiresWorldRestart(false);

        if (config.hasChanged()) {
            config.save();
        }
        sync();
        lastModified = file.lastModified();
    }

    /** 把配置文件里的值读进内存（开服、热重载、配置界面关闭后都会调）。 */
    public static synchronized void sync() {
        if (config == null) return;
        config.load();
        language = normalize(propLanguage.getString(), "language");
        logLanguage = normalize(propLogLanguage.getString(), "log_language");
    }

    /**
     * 服务器 tick 里调用：发现配置文件被外部改过就热更新。
     * 自带 2 秒节流，正常跑图时几乎不碰文件系统。
     */
    public static void watchForExternalEdit() {
        if (config == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_INTERVAL_MS) return;
        lastPollAt = now;
        long stamp = config.getConfigFile().lastModified();
        if (stamp != lastModified) {
            lastModified = stamp;
            sync();
        }
    }

    /** 配置文件是否已经就绪（preInit 之前为 false）。 */
    public static boolean isReady() {
        return config != null;
    }

    /** 供游戏内配置界面（BanishmentConfigGui / ModGuiFactory）使用；preInit 之前为 null。 */
    public static Configuration get() {
        return config;
    }

    /** 当前语言的可读名字，双语时返回 "zh+en"，用于开服提示与控制台打印。 */
    public static String describe(String mode) {
        if (ZH.equals(mode)) return "中文 (zh)";
        if (EN.equals(mode)) return "English (en)";
        return "中英双语 (both)";
    }

    /** 中文名、英文名、别名统统认；认不出来就退回双语并留一行提示。 */
    private static String normalize(String raw, String keyName) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return BOTH;
        // 先判「双语」的各种写法，避免 zh_en 被 zh_ 前缀误判成只中文
        if (v.equals("both") || v.equals("bilingual") || v.equals("all") || v.equals("zh_en")
                || v.equals("en_zh") || v.equals("zh+en") || v.equals("双语") || v.equals("中英")) {
            return BOTH;
        }
        if (v.equals("zh") || v.equals("cn") || v.startsWith("zh_") || v.startsWith("zh-")
                || v.equals("chinese") || v.equals("中文") || v.equals("简体中文")) {
            return ZH;
        }
        if (v.equals("en") || v.startsWith("en_") || v.startsWith("en-")
                || v.equals("english") || v.equals("英文") || v.equals("英语")) {
            return EN;
        }
        L10n.warn("配置项 " + keyName + " 的值 \"" + raw + "\" 不认识（可选 zh / en / both），已按双语处理 / "
                + "unknown value for " + keyName + " (use zh, en or both), falling back to bilingual");
        return BOTH;
    }
}
