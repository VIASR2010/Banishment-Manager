package cn.blockforge.generated.banishmentdimension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/**
 * 放逐状态管理核心。作者：VIASR，r20 行为等价重写（Fabric 1.21.1）。
 *
 * 背包全程不使用任何序列化存储——切换时用原版 ItemStack 对象在内存里对调，
 * 物品永远是「活的」原版对象，对任何自定义物品模组零侵入（与 r19 一致）。
 *
 * r19 的即时拦截靠 Forge 的维度切换事件；1.21.1 Fabric 没有该事件，这里改用
 * 「每刻位置见证（last-seen）+ 边界检测」实现同一语义：任何跨维度移动在发生的
 * 当刻即被处理（比事件只晚半个 tick），外加配置周期（默认 10 秒）的巡检兜底。
 * tick 层面相对 r19 的优化：落盘从每刻一次收敛为每周期一次（快照粒度不变），
 * 目标选择先查表再传送，空表时循环基本零开销。
 */
public final class BanishmentManager {

    /** 全部放逐状态（UUID → State）。包内可见：BanishmentStorage 载入时直接写这张表。 */
    static final Map<UUID, State> STATES = new HashMap<UUID, State>();
    /**
     * 非放逐玩家进入放逐维度前的原位记录（供 escape 与巡检返还原点）。
     * 只在内存中维护，跨会话不持久化（与 r19 一致）：重启后若仍有人混在里面，
     * 巡检会送回主世界出生点兜底。
     */
    static final Map<UUID, EntryPos> ENTRY_POSITIONS = new HashMap<UUID, EntryPos>();
    /** 每刻记录的玩家位置见证（维度 + 坐标 + 朝向）：跨界检测的数据源。 */
    private static final Map<UUID, Seen> LAST_SEEN = new HashMap<UUID, Seen>();
    private static long tickCounter = 0L;

    private BanishmentManager() {}

    /** 单个被放逐玩家的全部状态（内存对象 + 文件快照，物品只以原版 ItemStack 引用存在）。 */
    public static final class State {
        public final UUID uuid;
        public String playerName;
        public RegistryKey<World> originDim = World.OVERWORLD;
        /** 放逐前的重生点（维度 + 坐标 + 角度 + 是否强制；维度为 null 表示未设置） */
        public RegistryKey<World> originSpawnDim;
        public BlockPos originSpawnPos;
        public float originSpawnAngle;
        public boolean originSpawnForced;
        public double ox, oy, oz;
        public float yaw, pitch;
        public GameMode originGameType = GameMode.SURVIVAL;
        /** 原世界背包（内存对象数组：36 主背包 + 4 护甲 + 1 副手），每次变动同步写文件 */
        public final ItemStack[] homeMain = newEmpty(36);
        public final ItemStack[] homeArmor = newEmpty(4);
        public final ItemStack[] homeOff = newEmpty(1);
        /** 放逐维度背包（初始为空；始终由玩家身体的原版机制携带，不落我们的文件） */
        public final ItemStack[] banishMain = newEmpty(36);
        public final ItemStack[] banishArmor = newEmpty(4);
        public final ItemStack[] banishOff = newEmpty(1);
        /** true = 本状态由 banishment 文件夹载入（重启后首次对调走「保留快照」合并，防回档丢包） */
        public boolean fromDisk = false;
        /** true = 管理员对离线玩家执行了 unbanish：下次登录时执行解除（含从放逐维度送回原位） */
        public boolean pendingRelease = false;
        /** 最近一次成功落盘的时间戳（毫秒），仅用于诊断/列表展示 */
        public long savedAt = 0L;
        /**
         * 定时放逐的剩余「在线时长」（毫秒）。
         * &gt;0 = 定时放逐：只在该玩家在线时倒计时，下线冻结、写进文件，
         *     重新上线续走，归零自动解除放逐。
         * &lt;=0 = 无限期放逐（/banish 默认），由管理员用 /unbanish 解除。
         */
        public long banishtimeRemainingMs = 0L;
        /**
         * 上一次做在线计时的时间戳（毫秒），用于按真实流逝时间扣减剩余。
         * 玩家离线或尚未开始计时时为 0；登录/放逐时置零即可让倒计时从当下起算，
         * 从而保证「离线时间一分都不算」。
         */
        public long banishtimeLastMs = 0L;
        /**
         * 本次解除动作发给被放逐者的通知文案键。默认「解除放逐」；定时到期走
         * releaseTimedExpired 时会临时改成「放逐已到期」。
         * 只在一次解除动作期间有效，随状态移除而作废，不落盘。
         */
        public String releaseNotice = "msg.notice.released";

        State(UUID uuid, String name) {
            this.uuid = uuid;
            this.playerName = name;
        }
    }

    private static ItemStack[] newEmpty(int n) {
        ItemStack[] a = new ItemStack[n];
        for (int i = 0; i < n; i++) a[i] = ItemStack.EMPTY;
        return a;
    }

    /** 非放逐玩家进入放逐维度前所处的维度与坐标（进入前的原位）。 */
    public static final class EntryPos {
        public final RegistryKey<World> dim;
        public final double x, y, z;
        public final float yaw, pitch;

        EntryPos(RegistryKey<World> dim, double x, double y, double z, float yaw, float pitch) {
            this.dim = dim;
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }

    /** 每刻巡检记录的「玩家上一次所在维度与坐标」。 */
    private static final class Seen {
        final RegistryKey<World> dim;
        final double x, y, z;
        final float yaw, pitch;

        Seen(RegistryKey<World> dim, double x, double y, double z, float yaw, float pitch) {
            this.dim = dim; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static State get(PlayerEntity player) {
        return player == null ? null : STATES.get(player.getUuid());
    }

    public static boolean isBanished(PlayerEntity player) {
        return get(player) != null;
    }

    private static boolean inBanishmentDim(PlayerEntity player) {
        return player.getWorld().getRegistryKey().equals(BanishmentDimensions.active());
    }

    private static boolean inAnyBanishmentDim(PlayerEntity player) {
        return BanishmentDimensions.isBanishment(player.getWorld().getRegistryKey());
    }

    public static int count() { return STATES.size(); }

    /** 全部放逐记录（含离线与待解除的），banish list 用。 */
    public static List<State> banishedStates() {
        return new ArrayList<State>(STATES.values());
    }

    public static List<String> banishedNames() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<UUID, State> e : STATES.entrySet()) out.add(e.getValue().playerName);
        return out;
    }

    /** 离线玩家按名字回查放逐记录。 */
    public static State findByName(String name) {
        for (Map.Entry<UUID, State> e : STATES.entrySet()) {
            if (e.getValue().playerName.equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static ServerWorld overworld(MinecraftServer server) {
        return server.getWorld(World.OVERWORLD);
    }

    // ------------------------------------------------------------------
    // 放逐与解除
    // ------------------------------------------------------------------

    /** 执行放逐：记录原世界坐标、游戏模式、原重生点与当前背包，再切到放逐维度。 */
    public static void banish(ServerPlayerEntity target, String by) {
        MinecraftServer server = target.getServer();
        if (inAnyBanishmentDim(target)) {
            target.sendMessage(L10n.bi("msg.err.already_in_dim"));
            return;
        }
        ServerWorld dest = BanishmentDimensions.activeWorld(server);
        if (dest == null) {
            target.sendMessage(L10n.bi("msg.err.dim_not_loaded"));
            return;
        }
        State s = new State(target.getUuid(), target.getGameProfile().getName());
        s.originDim = target.getWorld().getRegistryKey();
        s.originSpawnDim = target.getSpawnPointDimension();
        s.originSpawnPos = target.getSpawnPointPosition();
        s.originSpawnAngle = target.getSpawnAngle();
        s.originSpawnForced = target.isSpawnForced();
        s.ox = target.getX(); s.oy = target.getY(); s.oz = target.getZ();
        s.yaw = target.getYaw(); s.pitch = target.getPitch();
        s.originGameType = target.interactionManager.getGameMode();
        STATES.put(s.uuid, s);
        ENTRY_POSITIONS.remove(s.uuid);
        BanishmentStorage.save(server, s); // 切维前先落盘：就算这一刻之后立刻崩溃，记录也在

        target.closeHandledScreen();
        target.sendMessage(L10n.bi("msg.notice.banished", by == null ? L10n.ACTOR_SYSTEM : by));
        playTeleportSound(target);
        enhancedFeedback(target, target.getServerWorld(), target.getX(), target.getY(), target.getZ());
        target.teleport(dest, BanishmentDimensions.SAFE_X, BanishmentDimensions.SAFE_Y, BanishmentDimensions.SAFE_Z, 0.0F, 0.0F);
        onEnterDimension(target);
        updateSeen(target);
    }

    /**
     * 进入放逐维度（自己的放逐流程、外部传送都走这里）：
     * 把此刻身上的物品存进快照，再换上 TA 在放逐维度原本的那套（首次进入即空背包）。
     */
    public static void onEnterDimension(ServerPlayerEntity player) {
        State s = get(player);
        if (s == null) return;
        boolean mergeFromDisk = s.fromDisk;
        snapshotInto(player, s.homeMain, s.homeArmor, s.homeOff, mergeFromDisk);
        applyFrom(player, s.banishMain, s.banishArmor, s.banishOff);
        s.fromDisk = false;
        if (ModConfig.adventureMode) player.changeGameMode(GameMode.ADVENTURE);
        player.setSpawnPoint(BanishmentDimensions.active(), BanishmentDimensions.SAFE_POS,
                0.0F, true, false);
        refreshEffects(player);
        BanishmentStorage.save(player.getServer(), s);
    }

    /** 越狱离开：先把身上的放逐背包收回 banish 数组，再把原世界背包换回去。 */
    public static void onLeaveDimension(ServerPlayerEntity player) {
        State s = get(player);
        if (s == null) return;
        snapshotInto(player, s.banishMain, s.banishArmor, s.banishOff, false);
        applyFrom(player, s.homeMain, s.homeArmor, s.homeOff);
    }

    /** 管理员解除放逐：回到原位、原模式、原背包。 */
    public static void release(ServerPlayerEntity target, String by) {
        State s = get(target);
        if (s == null) return;
        MinecraftServer server = target.getServer();
        target.closeHandledScreen();
        playTeleportSound(target);
        if (inBanishmentDim(target)) {
            ServerWorld dest = server.getWorld(s.originDim);
            if (dest == null) {
                fallbackRelease(target, s, server);
                return;
            }
            target.teleport(dest, s.ox, s.oy, s.oz, s.yaw, s.pitch);
            enhancedFeedback(target, dest, s.ox, s.oy, s.oz);
            finishRelease(target, s, by, true);
        } else {
            // 玩家当前不在放逐维度（崩溃回档被巡检拉回前的空档等）：原地交接背包并恢复即可
            finishRelease(target, s, by, false);
        }
        updateSeen(target);
    }

    /** 解除收尾：落地后统一走这里——换回原背包、恢复模式与重生点、清效果、删档。 */
    public static void finishRelease(ServerPlayerEntity player, State s, String by, boolean dropCarried) {
        if (dropCarried) {
            // 身上的放逐维度物品（通常空）在落地坐标掉出，绝不留在身上或消失
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < inv.main.size(); i++) dropIfPresent(player, inv.main.get(i));
            for (int i = 0; i < inv.armor.size(); i++) dropIfPresent(player, inv.armor.get(i));
            dropIfPresent(player, inv.offHand.get(0));
        }
        applyFrom(player, s.homeMain, s.homeArmor, s.homeOff);
        player.changeGameMode(s.originGameType);
        if (s.originSpawnDim != null) {
            BlockPos pos = s.originSpawnPos != null ? s.originSpawnPos
                    : BlockPos.ofFloored(s.ox, s.oy, s.oz);
            player.setSpawnPoint(s.originSpawnDim, pos, s.originSpawnAngle,
                    s.originSpawnForced || s.originSpawnPos != null, false);
        } else {
            ServerWorld ow = overworld(player.getServer());
            if (ow != null) {
                player.setSpawnPoint(World.OVERWORLD, ow.getSpawnPos(), 0.0F, false, false);
            }
        }
        removeEffects(player);
        STATES.remove(s.uuid);
        BanishmentStorage.delete(player.getServer(), s.uuid);
        playTeleportSound(player);
        player.sendMessage(L10n.bi(s.releaseNotice, by == null ? L10n.ACTOR_SYSTEM : by));
    }

    /** 解除时原维度不可用：不没收物品，原地恢复背包与模式，快照掉落在脚下。 */
    private static void fallbackRelease(ServerPlayerEntity player, State s, MinecraftServer server) {
        player.sendMessage(L10n.bi("msg.warn.fallback_drop"));
        applyFrom(player, s.homeMain, s.homeArmor, s.homeOff);
        removeEffects(player);
        STATES.remove(s.uuid);
        BanishmentStorage.delete(player.getServer(), s.uuid);
        ejectToOverworld(player, server, false);
    }

    /** 对离线玩家挂「待解除」标记：等 TA 下次登录时执行完整解除。 */
    public static void markPendingRelease(MinecraftServer server, State s) {
        s.pendingRelease = true;
        BanishmentStorage.save(server, s);
    }

    /** 离线待解除的登录后处理。 */
    public static void releaseOfflinePending(ServerPlayerEntity player) {
        State s = get(player);
        if (s == null) return;
        s.pendingRelease = false;
        if (inBanishmentDim(player)) {
            release(player, L10n.ACTOR_ADMIN);
            return;
        }
        // 回档导致人已不在放逐维度：先把身上的物品合并进快照（防覆盖丢包），再原地解除
        snapshotInto(player, s.homeMain, s.homeArmor, s.homeOff, true);
        finishRelease(player, s, L10n.ACTOR_ADMIN, false);
        updateSeen(player);
    }

    // ------------------------------------------------------------------
    // 定时放逐（/banishtime）：在线计时、下线冻结、写进文件、到期自动解除
    // ------------------------------------------------------------------

    /** 剩余在线时长向上取整的分钟数（用于展示）。非定时（<=0）返回 0。 */
    public static long remainingMinutes(State s) {
        if (s.banishtimeRemainingMs <= 0L) return 0L;
        return (s.banishtimeRemainingMs + 59999L) / 60000L;
    }

    /** /banishtime add：给已放逐玩家的剩余时长追加分钟，并落盘。 */
    public static void addBanishTime(MinecraftServer server, State s, int minutes) {
        s.banishtimeRemainingMs += (long) minutes * 60000L;
        if (s.banishtimeLastMs == 0L) {
            s.banishtimeLastMs = System.currentTimeMillis();
        }
        BanishmentStorage.save(server, s);
        ServerPlayerEntity mp = server.getPlayerManager().getPlayer(s.uuid);
        if (mp != null) {
            mp.sendMessage(L10n.bi("msg.notice.banishtime_add", String.valueOf(minutes),
                    String.valueOf(remainingMinutes(s))));
        }
    }

    /** /banishtime set：把已放逐玩家的剩余时长重设为指定分钟，并落盘。 */
    public static void setBanishTime(MinecraftServer server, State s, int minutes) {
        s.banishtimeRemainingMs = (long) minutes * 60000L;
        if (s.banishtimeLastMs == 0L) {
            s.banishtimeLastMs = System.currentTimeMillis();
        }
        BanishmentStorage.save(server, s);
        ServerPlayerEntity mp = server.getPlayerManager().getPlayer(s.uuid);
        if (mp != null) {
            mp.sendMessage(L10n.bi("msg.notice.banishtime_set", String.valueOf(minutes),
                    String.valueOf(remainingMinutes(s))));
        }
    }

    /**
     * 定时放逐到期自动解除：与 /unbanish 相同地把玩家送回原位并还原背包/游戏模式，
     * 但给被放逐者的通知改为「放逐已到期」（releaseNotice），并在控制台留一行日志。
     */
    public static void releaseTimedExpired(ServerPlayerEntity player) {
        State s = get(player);
        if (s == null) return;
        s.banishtimeRemainingMs = 0L;
        s.releaseNotice = "msg.notice.expired";
        L10n.log("log.banishtime_expired", s.playerName);
        release(player, L10n.ACTOR_SYSTEM);
    }

    /**
     * 越狱拉回：被放逐玩家以非解除方式离开 114（死亡重生、外部传送等）→ 立刻拉回。
     * 放逐维度若因异常未加载，则自动解除，保证「任何进入的人都能离开」。
     */
    public static void pullback(ServerPlayerEntity mp) {
        State s = get(mp);
        if (s == null) return;
        ServerWorld dest = BanishmentDimensions.activeWorld(mp.getServer());
        if (dest == null) {
            mp.sendMessage(L10n.bi("msg.warn.dim_unavailable"));
            finishRelease(mp, s, L10n.ACTOR_SYSTEM, true);
            return;
        }
        mp.sendMessage(L10n.bi("msg.warn.pullback"));
        playTeleportSound(mp);
        mp.teleport(dest, BanishmentDimensions.SAFE_X, BanishmentDimensions.SAFE_Y, BanishmentDimensions.SAFE_Z, 0.0F, 0.0F);
        enhancedFeedback(mp, dest, BanishmentDimensions.SAFE_X, BanishmentDimensions.SAFE_Y, BanishmentDimensions.SAFE_Z);
        onEnterDimension(mp);
        updateSeen(mp);
    }

    /** 把未被放逐、却站在放逐维度里的玩家送回主世界出生点。 */
    public static void ejectToOverworld(ServerPlayerEntity mp, MinecraftServer server, boolean notify) {
        ServerWorld ow = overworld(server);
        if (ow == null) {
            mp.sendMessage(L10n.bi("msg.err.overworld_unavailable"));
            return;
        }
        BlockPos sp = ow.getSpawnPos();
        if (notify) mp.sendMessage(L10n.bi("msg.notice.not_your_place"));
        playTeleportSound(mp);
        mp.teleport(ow, sp.getX() + 0.5D, sp.getY() + 1.0D, sp.getZ() + 0.5D, mp.getYaw(), mp.getPitch());
        updateSeen(mp);
    }

    /** 未被放逐的误入者：能回到进入前原位就回原位，否则退回主世界出生点（身上物品随行，不没收）。 */
    public static void returnNonBanishedToOrigin(ServerPlayerEntity mp) {
        EntryPos p = ENTRY_POSITIONS.remove(mp.getUuid());
        if (p != null) {
            ServerWorld dest = mp.getServer().getWorld(p.dim);
            if (dest != null) {
                mp.sendMessage(L10n.bi("msg.notice.returned_origin"));
                playTeleportSound(mp);
                mp.teleport(dest, p.x, p.y, p.z, p.yaw, p.pitch);
                updateSeen(mp);
                return;
            }
        }
        ejectToOverworld(mp, mp.getServer(), true);
    }

    /** 管理员强制把在线玩家送回主世界出生点（对未被放逐的违规进入者使用）。 */
    public static void forceReturn(ServerPlayerEntity mp, String by) {
        MinecraftServer server = mp.getServer();
        mp.closeHandledScreen();
        playTeleportSound(mp);
        ServerWorld ow = overworld(server);
        if (ow == null) {
            mp.sendMessage(L10n.bi("msg.err.overworld_unavailable"));
            return;
        }
        BlockPos sp = ow.getSpawnPos();
        List<ItemStack> carried = null;
        State s = get(mp);
        if (s != null) {
            // 兜底兼容：若被放逐玩家已越狱到外面，先正常解除（快照落回原背包），
            // 再把越狱期间拿到手的物品掉在出生点，一件不留也不丢
            carried = collectCarried(mp);
            finishRelease(mp, s, by == null ? L10n.ACTOR_SYSTEM : by, false);
        } else {
            mp.sendMessage(L10n.bi("msg.notice.returned", by == null ? L10n.ACTOR_SYSTEM : by));
        }
        mp.teleport(ow, sp.getX() + 0.5D, sp.getY() + 1.0D, sp.getZ() + 0.5D, mp.getYaw(), mp.getPitch());
        if (carried != null) {
            for (ItemStack st : carried) spawnItemAt(mp.getServerWorld(), mp.getX(), mp.getY(), mp.getZ(), st);
        }
        updateSeen(mp);
    }

    /** 记录「未被放逐的玩家进入放逐维度前」所处的维度与坐标（从上一刻的位置见证取）。 */
    private static void recordEntryPosition(ServerPlayerEntity mp, Seen prev) {
        if (mp == null) return;
        if (prev != null && !BanishmentDimensions.isBanishment(prev.dim)) {
            ENTRY_POSITIONS.put(mp.getUuid(),
                    new EntryPos(prev.dim, prev.x, prev.y, prev.z, prev.yaw, prev.pitch));
        }
    }

    // ------------------------------------------------------------------
    // 每刻巡检 + 周期状态循环（防自杀、防挖掘、防越狱、定期落盘）
    // ------------------------------------------------------------------

    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        final int cycle = Math.max(20, ModConfig.cycleTicks());
        final boolean sweep = tickCounter % cycle == 0L;

        RegistryKey<World> active = BanishmentDimensions.active();
        for (ServerPlayerEntity mp : server.getPlayerManager().getPlayerList()) {
            UUID uuid = mp.getUuid();
            Seen prev = LAST_SEEN.get(uuid);
            ServerWorld cur = mp.getServerWorld();
            boolean nowIn = cur.getRegistryKey().equals(active);

            // ---- 跨界边界检测（r19 的事件拦截在 1.21.1 的等价实现）----
            if (prev != null && !prev.dim.equals(cur.getRegistryKey())) {
                State s = get(mp);
                if (nowIn && s == null) {
                    // 未被放逐的玩家被外部手段送进了放逐维度：记原位 + 提示可自救
                    recordEntryPosition(mp, prev);
                    mp.sendMessage(L10n.bi("msg.notice.escape_hint"));
                } else if (nowIn && s != null && !s.pendingRelease) {
                    // 被放逐玩家从外部渠道（指令、模组等）进入：与放逐流程同一套换装逻辑
                    onEnterDimension(mp);
                } else if (!nowIn && prev.dim.equals(active) && s != null && !s.pendingRelease) {
                    // s.releasing 的窗口内 banish 流程自己会收尾，这里不重复处理
                    onLeaveDimension(mp);
                    pullback(mp);
                    continue;
                }
            }
            LAST_SEEN.put(uuid, seenOf(mp));

            // ---- 每刻：被放逐玩家在外面（回档、死亡重生、变体切换等）→ 立刻拉回当前变体 ----
            State s = get(mp);
            if (s == null || s.pendingRelease) continue;
            if (!nowIn) {
                pullback(mp);
                continue;
            }

            // ---- 每刻：定时放逐倒计时（只算在线时长；下线冻结、登录续走，归零自动解除）----
            if (s.banishtimeRemainingMs > 0L) {
                long now = System.currentTimeMillis();
                if (s.banishtimeLastMs == 0L) {
                    // 本轮在线的计时起点（登录/放逐后的第一刻），离线时间一分不算
                    s.banishtimeLastMs = now;
                } else {
                    long delta = now - s.banishtimeLastMs;
                    s.banishtimeLastMs = now;
                    if (delta > 0L) s.banishtimeRemainingMs -= delta;
                }
                if (s.banishtimeRemainingMs <= 0L) {
                    s.banishtimeRemainingMs = 0L;
                    releaseTimedExpired(mp); // 到期：与 /unbanish 同一套解除流程
                    continue;
                }
            }

            // ---- 每刻：维度内防自杀（饱食+抗性+防火+疲劳）、防挖掘（疲劳）----
            // 四个效果都是隐藏图标的增益；循环周期一到就整体刷新，中断永不留窗口。
            refreshEffects(mp);
            // ---- 周期兜底落盘：崩溃最多回档到 10 秒前的背包状态 ----
            if (sweep) BanishmentStorage.save(server, s);
        }

        // ---- 周期性巡检：把未被放逐却滞留在放逐维度的人送回原位 ----
        if (sweep) {
            ServerWorld w = server.getWorld(active);
            if (w == null) return;
            for (ServerPlayerEntity mp : w.getPlayers()) {
                State s = get(mp);
                if (s != null && !s.pendingRelease) {
                    // 被放逐玩家：断线重连或重启回到 114 出生点时，确保模式、出生点、效果都到位
                    if (ModConfig.adventureMode && mp.interactionManager.getGameMode() != GameMode.ADVENTURE) {
                        mp.changeGameMode(GameMode.ADVENTURE);
                    }
                    mp.setSpawnPoint(active, BanishmentDimensions.SAFE_POS, 0.0F, true, false);
                } else if (s == null) {
                    returnNonBanishedToOrigin(mp);
                }
            }
            // ---- 周期性清实体：放逐维度内只保留玩家与掉落物（无生物、无经验球等） ----
            if (ModConfig.clearEntities) clearEntities(w);
            // ---- 增强反馈（默认关）：维度内环境灰雾 ----
            if (ModConfig.enhancedFeedback) {
                for (ServerPlayerEntity mp : w.getPlayers()) {
                    ambientHaze(mp.getServerWorld(), mp.getX(), mp.getY(), mp.getZ());
                }
            }
        }
    }

    private static Seen seenOf(ServerPlayerEntity mp) {
        return new Seen(mp.getWorld().getRegistryKey(), mp.getX(), mp.getY(), mp.getZ(),
                mp.getYaw(), mp.getPitch());
    }

    /** 放逐/拉回流程内部传送完成后调用：把位置见证直接对齐到当前，避免边界检测重复触发。 */
    public static void updateSeen(ServerPlayerEntity mp) {
        if (mp != null) LAST_SEEN.put(mp.getUuid(), seenOf(mp));
    }

    public static void onQuit(ServerPlayerEntity mp) {
        LAST_SEEN.remove(mp.getUuid());
    }

    private static void clearEntities(ServerWorld w) {
        List<Entity> doomed = new ArrayList<Entity>();
        for (Entity e : w.iterateEntities()) {
            if (!(e instanceof PlayerEntity) && !(e instanceof ItemEntity)) doomed.add(e);
        }
        for (int i = 0; i < doomed.size(); i++) doomed.get(i).discard();
    }

    /** 关服时把全部放逐记录落盘（含离线与未上线的），并清空内存状态。 */
    public static void persistAllOnStop(MinecraftServer server) {
        int saved = 0;
        for (Map.Entry<UUID, State> e : STATES.entrySet()) {
            State s = e.getValue();
            ServerPlayerEntity mp = server.getPlayerManager().getPlayer(s.uuid);
            if (mp != null && !s.pendingRelease) {
                // 在线玩家用此刻身上的最新鲜数据（不靠 10 秒前的旧快照）
                snapshotInto(mp, s.homeMain, s.homeArmor, s.homeOff, false);
            }
            BanishmentStorage.save(server, s);
            saved++;
        }
        STATES.clear();
        LAST_SEEN.clear();
        if (saved > 0) {
            L10n.log("log.server_stopping", String.valueOf(saved), BanishmentStorage.FOLDER);
        }
    }

    // ------------------------------------------------------------------
    // 效果
    // ------------------------------------------------------------------

    private static void refreshEffects(ServerPlayerEntity player) {
        int cycle = Math.max(20, ModConfig.cycleTicks());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, cycle, 4, true, false)); // 挖不动任何东西
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, cycle, 4, true, false));       // 免疫一切伤害
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, cycle, 0, true, false));  // 免疫火焰与岩浆
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, cycle, 9, true, false));        // 永不饿死
    }

    private static void removeEffects(PlayerEntity player) {
        player.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        player.removeStatusEffect(StatusEffects.RESISTANCE);
        player.removeStatusEffect(StatusEffects.FIRE_RESISTANCE);
        player.removeStatusEffect(StatusEffects.SATURATION);
    }

    // ------------------------------------------------------------------
    // 反馈（原版：末影人传送音；增强：屏幕微震 + 低音轰鸣 + 灰色粒子，默认关闭）
    // ------------------------------------------------------------------

    private static void playTeleportSound(Entity player) {
        if (!ModConfig.teleportSound) return;
        try {
            player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0F, 0.8F);
        } catch (Exception ignored) {
            // 音频失败不影响放逐流程
        }
    }

    private static void enhancedFeedback(ServerPlayerEntity mp, ServerWorld w, double x, double y, double z) {
        if (!ModConfig.enhancedFeedback) return;
        try {
            mp.tiltScreen(0.6D, -0.6D); // 原版屏幕微震（纯数据包，vanilla 客户端也收得到）
            w.playSound(null, x, y + 1.0D, z,
                    SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.8F, 0.45F); // 低音轰鸣
            w.spawnParticles(ParticleTypes.CLOUD, x, y + 1.0D, z, 40, 0.6D, 0.5D, 0.6D, 0.02D);
        } catch (Exception ignored) {
            // 反馈失败不影响放逐流程
        }
    }

    private static void ambientHaze(ServerWorld w, double x, double y, double z) {
        try {
            w.spawnParticles(ParticleTypes.CLOUD, x + (w.getRandom().nextDouble() - 0.5D) * 12.0D,
                    y + 1.0D, z + (w.getRandom().nextDouble() - 0.5D) * 12.0D, 2, 0.1D, 0.05D, 0.1D, 0.0D);
        } catch (Exception ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------
    // 背包内存对调（全程原版对象拷贝，零 NBT）
    // ------------------------------------------------------------------

    /** 把玩家此刻身上的物品逐格存进目标数组，然后清空玩家背包。keepMissing=true 时空格不清除对应快照。 */
    private static void snapshotInto(ServerPlayerEntity player, ItemStack[] main, ItemStack[] armor,
                                     ItemStack[] off, boolean keepMissing) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < main.length && i < inv.main.size(); i++) {
            ItemStack held = inv.main.get(i);
            if (!held.isEmpty()) main[i] = held.copy();
            else if (!keepMissing) main[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < armor.length && i < inv.armor.size(); i++) {
            ItemStack held = inv.armor.get(i);
            if (!held.isEmpty()) armor[i] = held.copy();
            else if (!keepMissing) armor[i] = ItemStack.EMPTY;
        }
        ItemStack held = inv.offHand.get(0);
        if (!held.isEmpty()) off[0] = held.copy();
        else if (!keepMissing) off[0] = ItemStack.EMPTY;
        clearInventory(player);
    }

    /** 把快照数组原样换上玩家身体（对象级还原，不走任何序列化）。 */
    private static void applyFrom(ServerPlayerEntity player, ItemStack[] main, ItemStack[] armor,
                                  ItemStack[] off) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < main.length && i < inv.main.size(); i++) inv.main.set(i, main[i]);
        for (int i = 0; i < armor.length && i < inv.armor.size(); i++) inv.armor.set(i, armor[i]);
        inv.offHand.set(0, off[0]);
        inv.selectedSlot = 0;
        inv.markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
    }

    private static void clearInventory(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.main.size(); i++) inv.main.set(i, ItemStack.EMPTY);
        for (int i = 0; i < inv.armor.size(); i++) inv.armor.set(i, ItemStack.EMPTY);
        inv.offHand.set(0, ItemStack.EMPTY);
        inv.selectedSlot = 0;
        inv.markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
    }

    /** 采集玩家此刻身上的全部物品（主背包 + 快捷栏 + 护甲 + 副手），逐格 copy。 */
    private static List<ItemStack> collectCarried(ServerPlayerEntity player) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.main.size(); i++) addIfPresent(out, inv.main.get(i));
        for (int i = 0; i < inv.armor.size(); i++) addIfPresent(out, inv.armor.get(i));
        addIfPresent(out, inv.offHand.get(0));
        return out;
    }

    private static void addIfPresent(List<ItemStack> list, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        list.add(stack.copy());
    }

    /** 把一个非空快照在指定坐标掉出。 */
    private static void spawnItemAt(ServerWorld world, double x, double y, double z, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity item = new ItemEntity(world, x, y, z, stack.copy());
        item.setPickupDelay(60);
        world.spawnEntity(item);
    }

    /** 身上的一个槽位若有物品，就在玩家脚下掉出来（带短暂拾取延迟防秒捡）。 */
    private static void dropIfPresent(ServerPlayerEntity player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        spawnItemAt(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), stack);
    }
}
