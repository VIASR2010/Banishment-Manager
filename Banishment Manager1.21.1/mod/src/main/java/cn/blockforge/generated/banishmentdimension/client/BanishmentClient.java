package cn.blockforge.generated.banishmentdimension.client;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import cn.blockforge.generated.banishmentdimension.BanishmentDimensions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * 客户端接线（仅装了本模组的客户端生效；原版客户端照常能进，只是天空为主世界样式）。
 *
 * r19 的标志性视觉是「永远明亮的灰色天空」：1.12.2 里是代码天空渲染器，
 * 1.21.1 用 Fabric 的按维度天空渲染器等价复刻——把整片天空清空成标志灰
 * （三个变体给三种灰度：经典 0.34 与 r19 完全一致；黑石与虚空各配一档更深的灰）。
 * 云和天气一并置空：放逐维度无云无雨，与原版的「与世隔绝」观感一致。
 * 地面与远景的灰雾由数据文件里的群系雾色负责，这部分原版客户端也能看到。
 */
public final class BanishmentClient implements ClientModInitializer {

    /** 经典灰天空的亮度（r19 同款：0.34 的等边灰）。 */
    private static final float TONE_CLASSIC = 0.34F;
    /** 黑石牢房：更深的压抑灰。 */
    private static final float TONE_DARK = 0.12F;
    /** 虚空基岩：近乎纯黑。 */
    private static final float TONE_VOID = 0.05F;

    @Override
    public void onInitializeClient() {
        register(BanishmentDimensions.CLASSIC, TONE_CLASSIC);
        register(BanishmentDimensions.DARK, TONE_DARK);
        register(BanishmentDimensions.VOID, TONE_VOID);
    }

    private static void register(RegistryKey<World> key, float tone) {
        DimensionRenderingRegistry.registerSkyRenderer(key, context -> {
            RenderSystem.clearColor(tone, tone, tone, 1.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, true);
        });
        DimensionRenderingRegistry.registerCloudRenderer(key, context -> {
            // 放逐维度无云
        });
        DimensionRenderingRegistry.registerWeatherRenderer(key, context -> {
            // 放逐维度无雨雪
        });
    }
}
