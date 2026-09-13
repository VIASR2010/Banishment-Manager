package cn.blockforge.generated.banishmentdimension;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

/**
 * 事件接线层。作者：VIASR，r20 行为等价重写。
 *
 * 即时拦截：登录、重生、每刻位置见证（在 BanishmentManager 里）三重防线，
 * 保证被放逐玩家在任何情况下都离不开放逐维度；反过来说，任何误入者也都走得掉
 * （登录、巡检、escape 三路兜底），杜绝「无法离开」型错误。
 * 维度内禁自然刷怪在 1.21.1 已改为数据驱动：群系 JSON 刷怪表为空 +
 * 维度类型全亮光照 + 周期清实体，三层保险与 r19 等效。
 */
public final class BanishmentEvents {

    private BanishmentEvents() {}

    public static void register() {
        // ---- 命令注册 ----
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> BanishmentCommands.register(dispatcher));

        // ---- 登录：续放逐 / 待解除 / 误入兜底 ----
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));

        // ---- 登出：立即落盘最新快照（不等周期巡检） ----
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(server, handler.player));

        // ---- 死亡重生：确保仍留在放逐维度并恢复状态 ----
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));

        // ---- 每刻：跨界检测 + 周期状态循环 + 巡检 + 配置热更新 ----
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ModConfig.watchForExternalEdit();
            BanishmentManager.onServerTick(server);
        });

        // ---- 开服：读回全部放逐记录（重启续放逐） ----
        ServerLifecycleEvents.SERVER_STARTED.register(BanishmentEvents::onServerStarted);

        // ---- 关服：全部落盘，下次开服自动续放 ----
        ServerLifecycleEvents.SERVER_STOPPING.register(BanishmentEvents::onServerStopping);
    }

    private static void onServerStarted(MinecraftServer server) {
        int n = BanishmentStorage.loadAll(server);
        if (n > 0) {
            L10n.log("log.server_started", BanishmentStorage.FOLDER, String.valueOf(n));
        }
        // 开服把当前生效的语言与变体打印出来，方便确认配置文件读对了
        L10n.log("log.language", ModConfig.describe(ModConfig.language),
                ModConfig.CONFIG_ID + ".json");
        L10n.log("log.variant", ModConfig.variant, ModConfig.describe(ModConfig.language));
    }

    private static void onServerStopping(MinecraftServer server) {
        BanishmentManager.persistAllOnStop(server);
    }

    private static void onJoin(MinecraftServer server, ServerPlayerEntity mp) {
        BanishmentManager.updateSeen(mp);
        BanishmentManager.State s = BanishmentManager.get(mp);
        if (s != null) {
            // 定时放逐只算在线时长：登录时把计时起点归零，离线那段一分都不扣
            s.banishtimeLastMs = 0L;
            if (s.pendingRelease) {
                // 离线期间管理员已解除：上线即刻执行完整解除
                BanishmentManager.releaseOfflinePending(mp);
            } else if (!mp.getWorld().getRegistryKey().equals(BanishmentDimensions.active())) {
                if (mp.getWorld().getRegistryKey().equals(s.originDim)) {
                    // 放逐期间断线重连且回到放逐维度外（崩溃回档等）：拉回
                    BanishmentManager.pullback(mp);
                } else {
                    // 断线重连但被回档到第三个维度：先按当前状态解除回原位，再拉回
                    BanishmentManager.pullback(mp);
                }
            } else {
                reassert(mp);
            }
        } else if (mp.getWorld().getRegistryKey().equals(BanishmentDimensions.active())) {
            // 不在放逐名单却身在放逐维度（记录文件被管理员手动删除等）：
            // 为避免玩家困在无物维度，立即送回进入前原位；无原位记录则退回主世界出生点
            BanishmentManager.returnNonBanishedToOrigin(mp);
        }
    }

    /** 重连回维度时的状态再确认：模式、重生点、增益全部到位。 */
    private static void reassert(ServerPlayerEntity mp) {
        if (ModConfig.adventureMode) mp.changeGameMode(GameMode.ADVENTURE);
        mp.setSpawnPoint(BanishmentDimensions.active(), BanishmentDimensions.SAFE_POS, 0.0F, true, false);
    }

    private static void onDisconnect(MinecraftServer server, ServerPlayerEntity mp) {
        BanishmentManager.State s = BanishmentManager.get(mp);
        if (s != null && !s.pendingRelease) {
            BanishmentStorage.save(server, s);
        }
        BanishmentManager.onQuit(mp);
    }

    private static void onRespawn(ServerPlayerEntity mp) {
        BanishmentManager.State s = BanishmentManager.get(mp);
        BanishmentManager.updateSeen(mp);
        if (s == null || s.pendingRelease) return;
        if (!mp.getWorld().getRegistryKey().equals(BanishmentDimensions.active())) {
            // 重生点被回档等异常挪走了：只拉回，不碰背包（死亡已清空，对调交给进入流程）
            BanishmentManager.pullback(mp);
        } else {
            reassert(mp);
        }
    }
}
