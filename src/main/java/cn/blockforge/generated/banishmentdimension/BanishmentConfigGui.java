package cn.blockforge.generated.banishmentdimension;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

/**
 * 游戏内的配置页面：主界面（或暂停菜单）Mods → 选中「放逐 / Banishment」→ Config。（作者：VIASR）
 *
 * 页面项直接由 config/banishment.cfg 的 [general] 段生成：
 * language 与 log_language 都声明了合法取值（zh / en / both），
 * Forge 会自动把它们做成可点击循环的选项按钮；鼠标悬停显示注释里的双语说明。
 *
 * 关掉页面时 Forge 负责写回 .cfg，本类额外立刻把新值读进内存，所以聊天语言当场切换，
 * 不用重启游戏，也不用重新进存档。
 *
 * 说明：这个页面属于客户端。只有玩家自己也装了本模组的客户端时才能在列表里看到它；
 * 纯服务端分发时，请照旧直接编辑 config/banishment.cfg（效果完全一样）。
 */
public class BanishmentConfigGui extends GuiConfig {

    public BanishmentConfigGui(GuiScreen parentScreen) {
        super(parentScreen, configElements(), BanishmentMod.MOD_ID,
                false, false, configTitle());
    }

    @Override
    public void onGuiClosed() {
        // super 里 Forge 把界面上的改动落盘到 banishment.cfg
        super.onGuiClosed();
        // 再把文件里的新值读回内存，聊天/日志语言立即生效
        ModConfig.sync();
    }

    /** [general] 段的可视项；配置文件尚未就绪时返回空列表（不崩，页面只是空白）。 */
    private static List<IConfigElement> configElements() {
        Configuration cfg = ModConfig.get();
        if (cfg == null) {
            return new ArrayList<IConfigElement>();
        }
        return new ConfigElement(cfg.getCategory(Configuration.CATEGORY_GENERAL)).getChildElements();
    }

    /** 页面底部那行「Config: .../config/banishment.cfg」。 */
    private static String configTitle() {
        Configuration cfg = ModConfig.get();
        if (cfg == null) {
            return "banishment.cfg";
        }
        return getAbridgedConfigPath(cfg.toString());
    }
}
