package cn.blockforge.generated.banishmentdimension;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.GameType;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;

/**
 * 即时事件拦截层。作者：VIASR。
 *
 * 维度切换、登录、死亡重生三条出路全部即时拦截，任何被放逐玩家离开 114
 * 都会被立刻拉回；再加上每 10 秒的巡检兜底，越狱窗口几乎为零。
 */
public class BanishmentEvents {

    /**
     * 维度切换前：记录「非放逐玩家」进入 114 之前的位置。
     * 此时玩家坐标仍是原维度里的原生坐标，正好作为 /escape 与巡检的返还原点。
     * 被放逐玩家不记录（它们的原位在放逐记录里，走 /unbanish 逻辑）。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getDimension() != BanishmentManager.DIM_ID) return;
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        EntityPlayerMP mp = (EntityPlayerMP) event.getEntity();
        if (BanishmentManager.isBanished(mp)) return;
        BanishmentManager.recordEntryPosition(mp);
    }

    /** 即时拦截：任何原因的跨维度移动。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        BanishmentManager.State s = BanishmentManager.get(event.player);
        if (s == null) {
            // 未被放逐却进了放逐维度（如越权进入）：不立即踢回——让玩家可先用 /escape 回到
            // 进入前原位；这里只发一条提示，10 秒巡检会自动把他送回原位（或主世界出生点兜底）。
            if (event.toDim == BanishmentManager.DIM_ID && event.player instanceof EntityPlayerMP) {
                event.player.sendMessage(L10n.bi("msg.notice.escape_hint"));
            }
            return;
        }
        if (event.toDim == BanishmentManager.DIM_ID) {
            BanishmentManager.onEnterDimension(event.player);
        } else if (event.fromDim == BanishmentManager.DIM_ID) {
            if (s.releasing) {
                s.releasing = false;
                BanishmentManager.finishRelease(event.player, s, L10n.ACTOR_ADMIN, true);
            } else {
                // 越狱：先收回放逐背包再拉回
                BanishmentManager.onLeaveDimension(event.player);
                if (event.player instanceof EntityPlayerMP) {
                    BanishmentManager.pullback((EntityPlayerMP) event.player);
                }
            }
        }
    }

    /** 即时拦截：登录。 */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP mp = (EntityPlayerMP) event.player;
        BanishmentManager.State s = BanishmentManager.get(mp);
        if (s != null) {
            // 定时放逐：登录即把倒计时起点重置为当下，保证不在线的这段时间一分都不算
            s.banishtimeLastMs = System.currentTimeMillis();
            if (s.pendingRelease) {
                // 离线期间管理员已 /unbanish：上线即刻执行完整解除
                BanishmentManager.releaseOfflinePending(mp);
            } else if (mp.dimension != BanishmentManager.DIM_ID) {
                // 放逐期间断线重连且不在 114（崩溃回档等）：直接拉回
                BanishmentManager.pullback(mp);
            } else {
                mp.setGameType(GameType.ADVENTURE);
                mp.setSpawnPoint(BanishmentManager.SAFE_POS, true);
                mp.setSpawnDimension(BanishmentManager.DIM_ID);
                BanishmentManager.refreshEffects(mp);
            }
        } else if (mp.dimension == BanishmentManager.DIM_ID) {
            // 不在放逐名单却在 114（记录文件被管理员手动删除等）：
            // 为避免玩家困在无物维度，立即送回进入前原位；本会话没有原位记录则退回主世界
            // 出生点兜底（身上物品随行，不没收）。
            BanishmentManager.returnNonBanishedToOrigin(mp);
        }
    }

    /** 登出落盘：把最新的原背包快照立即写进 banishment/ 文件夹，不等 10 秒巡检。 */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BanishmentManager.State s = BanishmentManager.get(event.player);
        if (s != null && !s.releasing) {
            // 下线即把倒计时暂停：剩余时长已冻结，下一次登录再从当下继续
            s.banishtimeLastMs = 0L;
            BanishmentStorage.save(FMLCommonHandler.instance().getMinecraftServerInstance(), s);
        }
    }

    /** 即时拦截：死亡重生（防止用重生逃回放逐维度之外）。 */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP mp = (EntityPlayerMP) event.player;
        BanishmentManager.State s = BanishmentManager.get(mp);
        if (s == null) return;
        if (mp.dimension != BanishmentManager.DIM_ID) {
            // 只拉回，不碰背包：死亡时背包已空，背包对调交给进入 114 的事件链统一处理
            BanishmentManager.pullback(mp);
        } else {
            mp.setGameType(GameType.ADVENTURE);
            mp.setSpawnPoint(BanishmentManager.SAFE_POS, true);
            mp.setSpawnDimension(BanishmentManager.DIM_ID);
            BanishmentManager.refreshEffects(mp);
        }
    }

    /** 巡检 + 10 秒状态循环刷新。（1.12.2 的 TickEvent 没有 getServer()，从 FMLCommonHandler 拿） */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 配置文件被外部编辑过时热更新（内部已做 2 秒节流，平时不碰文件系统）
        ModConfig.watchForExternalEdit();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            BanishmentManager.onServerTick(server);
        }
    }

    /**
     * 放逐维度禁止一切自然刷怪。
     * 注意：本版本 Forge 的 CheckSpawn 只带 @HasResult、不带 @Cancelable，
     * 必须用 setResult(DENY)，调用 setCanceled 会抛异常。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (event.getEntityLiving() != null && event.getEntityLiving().dimension == BanishmentManager.DIM_ID) {
            event.setResult(Event.Result.DENY);
        }
    }

    /** SpecialSpawn 可取消：取消 + DENY 双保险。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSpecialSpawn(LivingSpawnEvent.SpecialSpawn event) {
        if (event.getEntityLiving() != null && event.getEntityLiving().dimension == BanishmentManager.DIM_ID) {
            event.setResult(Event.Result.DENY);
            event.setCanceled(true);
        }
    }
}
