package cn.blockforge.generated.banishmentdimension;

import java.util.Collections;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

/**
 * 客户端「模组列表 / Mods → 选中本模组 → Config」按钮的入口。（作者：VIASR）
 *
 * Forge 1.12.2 的规则：主界面 Mods 屏幕里那个 Config 按钮，只有当模组在 @Mod 注解里
 * 声明了 guiFactory（也就是本类）时才会亮起来；没声明的模组按钮是灰的或根本不显示。
 * 所以上一轮说的「游戏里改语言」要靠这个类才真正成立。
 *
 * 只在客户端被 Forge 实例化，专用服务端永远不会加载它，因此本模组依然不需要客户端安装。
 */
public class ModGuiFactory implements IModGuiFactory {

    /** Forge 启动客户端时调一次，本模组没有需要预热的界面资源。 */
    @Override
    public void initialize(Minecraft minecraftInstance) {
        // no-op
    }

    /** 告诉 Forge：本模组有配置界面，请把 Config 按钮亮出来。 */
    @Override
    public boolean hasConfigGui() {
        return true;
    }

    /** 玩家点 Config 时，Forge 调这里造一个配置页面。 */
    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        return new BanishmentConfigGui(parentScreen);
    }

    /** 不使用「游戏内暂停菜单 → 模组设置」那套运行时分类。 */
    @Override
    @SuppressWarnings("rawtypes")
    public Set runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
