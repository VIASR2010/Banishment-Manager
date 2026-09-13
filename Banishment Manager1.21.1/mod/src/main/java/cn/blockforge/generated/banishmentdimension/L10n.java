package cn.blockforge.generated.banishmentdimension;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 多语言取词工具（行为等价迁移自 r19 · 作者：VIASR）。
 *
 * 文案放在标准语言文件里（与原版客户端语言文件同一份，管理员改措辞不用动代码）：
 *   assets 下 banishment_dimension 命名空间的 zh_cn.json 中文、en_us.json 英文。
 * 发哪一种由配置文件 language.chat 决定：
 *   zh 只中文；en 只英文；both 中英双语（默认：中文在前一行、英文在后一行）。
 * 控制台那一路单独看 language.console。
 *
 * 占位符与原版一致：%1$s %2$s 按位置替换（String.format 同款语法）。
 * ACTOR_ADMIN、ACTOR_SYSTEM 哨兵值按目标语言替换成「管理员」或「An admin」、「系统」或「The system」。
 * 文案里的旧式 §X 颜色码在这里解析成现代样式（新版客户端不再渲染字面 § 码）。
 */
public final class L10n {

    private static final String LANG_DIR = "assets/banishment_dimension/lang";
    private static final String NS = "banishment_dimension.";

    /** 哨兵：动作发起人「管理员」。 */
    public static final String ACTOR_ADMIN = "\u0001actor.admin";
    /** 哨兵：动作发起人「系统」。 */
    public static final String ACTOR_SYSTEM = "\u0001actor.system";

    private static final Map<String, String> ZH = new HashMap<String, String>();
    private static final Map<String, String> EN = new HashMap<String, String>();
    private static boolean loaded = false;

    private L10n() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        load("zh_cn.json", ZH);
        load("en_us.json", EN);
        loaded = true;
    }

    private static void load(String file, Map<String, String> into) {
        Optional<ModContainer> container =
                FabricLoader.getInstance().getModContainer(BanishmentMod.MOD_ID);
        if (container.isEmpty()) return;
        Path path = container.get().findPath(LANG_DIR + "/" + file).orElse(null);
        if (path == null || !Files.isRegularFile(path)) {
            BanishmentMod.LOGGER.warn("[放逐/Banishment] 缺少翻译文件 {} / missing lang file", file);
            return;
        }
        try (InputStream in = Files.newInputStream(path);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JsonObject o = JsonParser.parseString(sb.toString()).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonPrimitive()) into.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            BanishmentMod.LOGGER.warn("[放逐/Banishment] 读取翻译文件失败 {} / failed to read: {}", file, e.toString());
        }
    }

    /** 取一种语言的原文；本语言缺键时退到另一语言，再缺就退到键名本身。 */
    private static String raw(Map<String, String> m, String key) {
        String v = m.get(NS + key);
        if (v == null) v = (m == ZH ? EN : ZH).get(NS + key);
        if (v == null) v = key;
        return v;
    }

    /** 取键对应的中、英文案（占位符已替换）。[0]=中文 [1]=英文。 */
    public static String[] pair(String key, Object... args) {
        ensureLoaded();
        return new String[] { format(raw(ZH, key), args, ZH), format(raw(EN, key), args, EN) };
    }

    /** 一条聊天消息（按配置的语言发）：单语一行，双语两行（中文在前、英文在后）。 */
    public static MutableText bi(String key, Object... args) {
        String[] p = pair(key, args);
        return lines(p[0], p[1]);
    }

    /** 手工拼好的中、英正文（如 banish 的 list 子命令名单），按配置的语言折叠成一行或两行。 */
    public static MutableText lines(String zhBody, String enBody) {
        ensureLoaded();
        String mode = ModConfig.language;
        if (ModConfig.ZH.equals(mode)) {
            return render("§7[" + raw(ZH, "prefix") + "] " + zhBody);
        }
        if (ModConfig.EN.equals(mode)) {
            return render("§7[" + raw(EN, "prefix") + "] " + enBody);
        }
        return render("§7[" + raw(ZH, "prefix") + "] " + zhBody)
                .append(Text.literal("\n"))
                .append(render("§7[" + raw(EN, "prefix") + "] " + enBody));
    }

    /** 命令用法提示（纯文本一行；双语用竖线分隔）。 */
    public static String usage(String key) {
        String[] p = pair(key);
        String mode = ModConfig.language;
        if (ModConfig.ZH.equals(mode)) return strip(p[0]);
        if (ModConfig.EN.equals(mode)) return strip(p[1]);
        return strip(p[0]) + "  |  " + strip(p[1]);
    }

    /** 双语控制台日志（服务端控制台、日志文件里），语言看 language.console。 */
    public static void log(String key, Object... args) {
        String[] p = pair(key, args);
        emit(p[0], p[1]);
    }

    /** 未经翻译的原样文字也走配置语言，中英各给一句。 */
    public static void warn(String zhText, String enText) {
        emit(zhText, enText);
    }

    /** 只有一句话可说时的控制台提示（例如配置值写错），中英混排一行。 */
    public static void warn(String mixed) {
        BanishmentMod.LOGGER.info("[放逐/Banishment] {}", mixed);
    }

    private static void emit(String zh, String en) {
        String mode = ModConfig.logLanguage;
        zh = strip(zh);
        en = strip(en);
        if (ModConfig.ZH.equals(mode)) {
            BanishmentMod.LOGGER.info("[放逐] {}", zh);
        } else if (ModConfig.EN.equals(mode)) {
            BanishmentMod.LOGGER.info("[Banishment] {}", en);
        } else {
            BanishmentMod.LOGGER.info("[放逐/Banishment] {}  ||  {}", zh, en);
        }
    }

    private static String format(String tpl, Object[] args, Map<String, String> lang) {
        if (args.length == 0 || tpl.indexOf('%') < 0) return tpl;
        Object[] a = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String && ((String) args[i]).startsWith("\u0001")) {
                a[i] = raw(lang, ((String) args[i]).substring(1));
            } else {
                a[i] = args[i];
            }
        }
        try {
            return String.format(tpl, a);
        } catch (Exception e) {
            return tpl;
        }
    }

    /** 剥掉旧式 §X 颜色码，得到纯文本（控制台与用法提示用）。 */
    public static String strip(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 把带旧式 §X 颜色码的文案解析成带样式的现代 MutableText。 */
    public static MutableText render(String legacy) {
        MutableText out = Text.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '\u00a7' && i + 1 < legacy.length()) {
                flush(out, buf, style);
                Formatting f = Formatting.byCode(legacy.charAt(++i));
                if (f == null) {
                    buf.append('\u00a7');
                } else if (f == Formatting.RESET) {
                    style = Style.EMPTY;
                } else if (f.isColor()) {
                    style = Style.EMPTY.withColor(f);
                } else {
                    style = style.withFormatting(f);
                }
                continue;
            }
            buf.append(c);
        }
        flush(out, buf, style);
        return out;
    }

    private static void flush(MutableText out, StringBuilder buf, Style style) {
        if (buf.length() == 0) return;
        out.append(Text.literal(buf.toString()).setStyle(style));
        buf.setLength(0);
    }
}
