package cn.blockforge.generated.banishmentdimension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 模组配置文件：config 目录下 banishment.json（作者：VIASR，r20 起的新配置结构）。
 *
 * 这一代改用更清晰的分类 JSON（按想法池「仅新配置结构」：不再兼容旧 .cfg 的键名，
 * 升级后管理员按新格式重设一次即可；好处是结构直观、可手写、可整份复制分发）。
 * 首次启动自动生成默认文件，各分类含义见生成的文件；改完存盘约 2 秒内热更新、不必重启。
 *
 *   language.chat     聊天消息语言：zh 只中文 / en 只英文 / both 中英双语（默认）
 *   language.console  控制台与日志文件的语言，取值同上（单独控制，不影响玩家聊天）
 *   dimension.variant 放逐维度变体：classic 经典灰天浅水（默认）/ dark 黑石牢房 / void 虚空基岩
 *   behavior          状态循环周期、冒险模式、实体巡检开关
 *   feedback          传送音效与沉浸增强反馈（默认关闭，避免干扰 PVP）
 *   storage           背包快照编码：plain 纯文本「物品|数量」（零 NBT 设计语言）/ enhanced 完整组件
 *
 * 写错值不会崩服：自动按默认处理并在控制台留一行提示。
 */
public final class ModConfig {

    /** 只中文。 */
    public static final String ZH = "zh";
    /** 只英文。 */
    public static final String EN = "en";
    /** 中英双语（默认）。 */
    public static final String BOTH = "both";

    /** 维度变体：经典灰天浅水。 */
    public static final String VARIANT_CLASSIC = "classic";
    /** 维度变体：黑石牢房。 */
    public static final String VARIANT_DARK = "dark";
    /** 维度变体：虚空基岩。 */
    public static final String VARIANT_VOID = "void";

    /** 背包快照编码：纯文本（原版设计语言，零 NBT）。 */
    public static final String ENC_PLAIN = "plain";
    /** 背包快照编码：完整组件 JSON（附魔也能保留）。 */
    public static final String ENC_ENHANCED = "enhanced";

    /** 文件名：banishment.json（配置目录下）。 */
    public static final String CONFIG_ID = "banishment";

    // ---- 当前生效值（volatile：tick 线程热更新，其他线程随时读） ----
    public static volatile String language = BOTH;
    public static volatile String logLanguage = BOTH;
    public static volatile String variant = VARIANT_CLASSIC;
    public static volatile int cycleSeconds = 10;
    public static volatile boolean adventureMode = true;
    public static volatile boolean clearEntities = true;
    public static volatile boolean teleportSound = true;
    public static volatile boolean enhancedFeedback = false;
    public static volatile String itemEncoding = ENC_PLAIN;

    private static Path file;
    private static long lastModified = -1L;
    private static long lastPollAt = 0L;
    /** 热重载的最小间隔（毫秒），正常跑图时几乎不碰文件系统。 */
    private static final long POLL_INTERVAL_MS = 2000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModConfig() {}

    /** 配置目录里的 banishment.json。 */
    public static Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_ID + ".json");
        }
        return file;
    }

    /** onInitialize 调用：文件不存在就写一份默认值，然后把当前值读进内存。 */
    public static void init() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Files.writeString(f, defaultJson(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            L10n.warn("配置文件写入失败 config write failed: " + e);
        }
        sync();
        try {
            lastModified = Files.getLastModifiedTime(f).toMillis();
        } catch (Exception ignored) {
            // 拿不到时间戳就当没改过：热更新退化为不触发，不影响运行
        }
    }

    /** 把配置文件里的值读进内存（开服、热重载都走这里）。任何异常都退回安全默认。 */
    public static synchronized void sync() {
        Path f = file();
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return; // 读不到就保持上一次生效值（或默认值）
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            language = normalizeLanguage(str(root, "language", "chat", language), "language.chat");
            logLanguage = normalizeLanguage(str(root, "language", "console", logLanguage), "language.console");
            variant = normalizeVariant(str(root, "dimension", "variant", variant));
            cycleSeconds = Math.min(3600, Math.max(1,
                    integer(root, "behavior", "cycle_seconds", cycleSeconds)));
            adventureMode = bool(root, "behavior", "adventure_mode", adventureMode);
            clearEntities = bool(root, "behavior", "clear_entities", clearEntities);
            teleportSound = bool(root, "feedback", "teleport_sound", teleportSound);
            enhancedFeedback = bool(root, "feedback", "enhanced", enhancedFeedback);
            itemEncoding = normalizeEncoding(str(root, "storage", "item_encoding", itemEncoding));
        } catch (Exception e) {
            L10n.warn("banishment.json 格式不正确，已按当前生效值继续运行 / bad config, keeping previous values: " + e);
        }
    }

    /**
     * 服务器 tick 里调用：发现配置文件被外部改过就热更新。
     * 自带 2 秒节流，正常跑图时几乎不碰文件系统。
     */
    public static void watchForExternalEdit() {
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_INTERVAL_MS) return;
        lastPollAt = now;
        try {
            long stamp = Files.getLastModifiedTime(file()).toMillis();
            if (stamp != lastModified) {
                lastModified = stamp;
                sync();
            }
        } catch (Exception ignored) {
            // 文件暂时不可读：下一轮再试
        }
    }

    /** 状态循环与巡检的周期（tick）。 */
    public static int cycleTicks() {
        return cycleSeconds * 20;
    }

    /** 给开服日志用的一行语言描述。 */
    public static String describe(String mode) {
        if (ZH.equals(mode)) return "zh（只中文 / Chinese only）";
        if (EN.equals(mode)) return "en（只英文 / English only）";
        return "both（中英双语 / bilingual）";
    }

    // ---- 归一化：大小写随意，常见别名也认 ----
    public static String normalizeLanguage(String rawValue, String keyName) {
        String v = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (v.equals("zh") || v.equals("zh_cn") || v.equals("zh-cn") || v.equals("chinese") || v.equals("cn")) return ZH;
        if (v.equals("en") || v.equals("en_us") || v.equals("en-us") || v.equals("english")) return EN;
        if (v.isEmpty() || v.equals("both") || v.equals("bilingual") || v.equals("all")) return BOTH;
        L10n.warn("配置 " + keyName + " 的值 \"" + rawValue + "\" 不认识，已按 both 处理 / unknown value, fell back to both");
        return BOTH;
    }

    public static String normalizeVariant(String rawValue) {
        String v = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("classic") || v.equals("gray") || v.equals("original")) return VARIANT_CLASSIC;
        if (v.equals("dark") || v.equals("blackstone") || v.equals("black")) return VARIANT_DARK;
        if (v.equals("void") || v.equals("bedrock_only") || v.equals("bare")) return VARIANT_VOID;
        L10n.warn("配置 dimension.variant 的值 \"" + rawValue + "\" 不认识，已按 classic 处理 / unknown value, fell back to classic");
        return VARIANT_CLASSIC;
    }

    public static String normalizeEncoding(String rawValue) {
        String v = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("plain") || v.equals("nbt_free") || v.equals("legacy")) return ENC_PLAIN;
        if (v.equals("enhanced") || v.equals("components") || v.equals("full")) return ENC_ENHANCED;
        L10n.warn("配置 storage.item_encoding 的值 \"" + rawValue + "\" 不认识，已按 plain 处理");
        return ENC_PLAIN;
    }

    // ---- 小工具：按「分类.键」取值，缺省回退 ----
    private static String str(JsonObject root, String cat, String key, String def) {
        JsonElement c = root.get(cat);
        if (c == null || !c.isJsonObject()) return def;
        JsonElement v = c.getAsJsonObject().get(key);
        return v == null || !v.isJsonPrimitive() ? def : v.getAsString();
    }

    private static int integer(JsonObject root, String cat, String key, int def) {
        JsonElement c = root.get(cat);
        if (c == null || !c.isJsonObject()) return def;
        JsonElement v = c.getAsJsonObject().get(key);
        try {
            return v == null ? def : v.getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject root, String cat, String key, boolean def) {
        JsonElement c = root.get(cat);
        if (c == null || !c.isJsonObject()) return def;
        JsonElement v = c.getAsJsonObject().get(key);
        try {
            return v == null ? def : v.getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private static String defaultJson() {
        JsonObject root = new JsonObject();
        JsonObject lang = new JsonObject();
        lang.addProperty("chat", BOTH);
        lang.addProperty("console", BOTH);
        root.add("language", lang);
        JsonObject dim = new JsonObject();
        dim.addProperty("variant", VARIANT_CLASSIC);
        root.add("dimension", dim);
        JsonObject behavior = new JsonObject();
        behavior.addProperty("cycle_seconds", 10);
        behavior.addProperty("adventure_mode", true);
        behavior.addProperty("clear_entities", true);
        root.add("behavior", behavior);
        JsonObject feedback = new JsonObject();
        feedback.addProperty("teleport_sound", true);
        feedback.addProperty("enhanced", false);
        root.add("feedback", feedback);
        JsonObject storage = new JsonObject();
        storage.addProperty("item_encoding", ENC_PLAIN);
        root.add("storage", storage);
        return GSON.toJson(root);
    }
}
