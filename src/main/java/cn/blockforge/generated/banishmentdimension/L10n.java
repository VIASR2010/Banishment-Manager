package cn.blockforge.generated.banishmentdimension;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * 多语言取词工具。作者：VIASR。
 *
 * 文案全部放在标准翻译文件里：
 *   assets/banishment_dimension/lang/zh_cn.lang  中文
 *   assets/banishment_dimension/lang/en_us.lang  英文
 * 发哪一种由配置文件 config/banishment.cfg 里的 S:language 决定：
 *   zh   只发中文
 *   en   只发英文
 *   both 中英两行合并在同一条消息里发出（中文在前、英文在后，默认）
 * 控制台那一路单独看 S:log_language。
 * 管理员直接改 .lang 文件即可调整措辞或再加一种语言，不用动代码。
 *
 * 占位符与原版一致：%1$s %2$s 按位置替换（java String.format 同款语法）。
 * ACTOR_ADMIN / ACTOR_SYSTEM 两个哨兵值会按目标语言分别替换成
 * 「管理员 / An admin」「系统 / The system」。
 */
public final class L10n {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String DIR = "assets/banishment_dimension/lang/";
    private static final String NS = "banishment_dimension.";

    /** 哨兵：动作发起人「管理员」，中英文案不同，由 pair() 按语言替换。 */
    public static final String ACTOR_ADMIN = "\u0001actor.admin";
    /** 哨兵：动作发起人「系统」。 */
    public static final String ACTOR_SYSTEM = "\u0001actor.system";

    private static final Map<String, String> ZH = new HashMap<String, String>();
    private static final Map<String, String> EN = new HashMap<String, String>();
    private static boolean loaded = false;

    private L10n() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        load("zh_cn.lang", ZH);
        load("en_us.lang", EN);
        loaded = true;
    }

    private static void load(String file, Map<String, String> into) {
        InputStream in = null;
        try {
            in = L10n.class.getClassLoader().getResourceAsStream(DIR + file);
            if (in == null) {
                System.err.println("[放逐/Banishment] 缺少翻译文件 " + DIR + file + " / missing lang file");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.charAt(0) == '#') continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                into.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (Exception e) {
            System.err.println("[放逐/Banishment] 读取翻译文件失败 " + file + " / failed to read: " + e);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
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
    public static ITextComponent bi(String key, Object... args) {
        String[] p = pair(key, args);
        return lines(p[0], p[1]);
    }

    /**
     * 手工拼好的中、英正文两行（如 /banish list 名单），按配置的语言取用：
     * 单语只留对应那一行并加前缀，双语两行都留。
     */
    public static ITextComponent lines(String zhBody, String enBody) {
        ensureLoaded();
        String mode = ModConfig.language;
        if (ModConfig.ZH.equals(mode)) {
            return new TextComponentString("§7[" + raw(ZH, "prefix") + "] " + zhBody);
        }
        if (ModConfig.EN.equals(mode)) {
            return new TextComponentString("§7[" + raw(EN, "prefix") + "] " + enBody);
        }
        return new TextComponentString(
                "§7[" + raw(ZH, "prefix") + "] " + zhBody + "\n§7[" + raw(EN, "prefix") + "] " + enBody);
    }

    /**
     * 命令用法提示：单语就一行，双语拼成「中文 §8| 英文」一行
     * （原版用法提示只显示首行，所以这里不分行）。
     */
    public static String usage(String key) {
        String[] p = pair(key);
        String mode = ModConfig.language;
        if (ModConfig.ZH.equals(mode)) return p[0];
        if (ModConfig.EN.equals(mode)) return p[1];
        return p[0] + " §8| §7" + p[1];
    }

    /** 双语控制台日志（服务端命令行/日志文件里中英各一句），语言看 S:log_language。 */
    public static void log(String key, Object... args) {
        String[] p = pair(key, args);
        emit(p[0], p[1]);
    }

    /** 未经翻译的原样文字也要走配置语言（如启动/异常提示），中英各给一句。 */
    public static void warn(String zhText, String enText) {
        emit(zhText, enText);
    }

    /** 只有一句话可说时的控制台提示（例如配置值写错），中英文混排在一行里。 */
    public static void warn(String mixed) {
        System.out.println("[放逐/Banishment] " + mixed);
    }

    private static void emit(String zh, String en) {
        String mode = ModConfig.logLanguage;
        if (ModConfig.ZH.equals(mode)) {
            System.out.println("[放逐] " + zh);
        } else if (ModConfig.EN.equals(mode)) {
            System.out.println("[Banishment] " + en);
        } else {
            System.out.println("[放逐/Banishment] " + zh + "  ||  " + en);
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
}
