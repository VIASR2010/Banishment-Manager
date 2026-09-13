package cn.blockforge.generated.banishmentdimension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * 放逐状态管理核心。作者：VIASR。
 *
 * 重要设计（按要求）：玩家背包全程不使用任何 NBT 序列化存储——
 * 切换维度时用原版 ItemStack 对象数组在内存里对调，物品永远是"活的"原版对象，
 * 对任何自定义物品/背包模组完全兼容。
 *
 * 持久化（本轮新增）：放逐记录与原背包快照写入服务端存档根目录的
 * banishment/ 文件夹（纯文本 JSON，见 BanishmentStorage）。开服自动载入、
 * 换袋/关服/登出自动落盘、解除自动删档——崩溃重启后放逐照样续，背包按最近
 * 一次保存点回档，物品不再因重启而蒸发。
 */
public final class BanishmentManager {

    /** 放逐维度 ID */
    public static final int DIM_ID = 114;
    /** 状态循环周期：10 秒 = 200 刻 */
    public static final int CYCLE_TICKS = 200;
    /** 放逐维度安全落点：脚站 Y=1（基岩地板顶面，脚踩基岩、身体泡在仅一层的浅水里，头露在水面外）。 */
    public static final double SAFE_X = 0.5D, SAFE_Y = 1.0D, SAFE_Z = 0.5D;
    /** 越狱巡检坐标（同安全落点） */
    public static final BlockPos SAFE_POS = new BlockPos(0, 1, 0);

    /** 包内可见：BanishmentStorage 载入/落盘时直接读写这张表。 */
    static final Map<UUID, State> STATES = new HashMap<UUID, State>();
    /**
     * 非放逐玩家进入 114 前的原位记录（供 /escape 与巡检返还原点）。
     * 只在内存中维护，跨会话不持久化：重启后若仍有人混在 114，巡检会退回主世界出生点兜底。
     */
    static final Map<UUID, EntryPos> ENTRY_POSITIONS = new HashMap<UUID, EntryPos>();
    private static long tickCounter = 0L;

    private BanishmentManager() {}

    /** 单个被放逐玩家的全部状态（内存对象 + 文件快照，物品只以原版 ItemStack 引用存在）。 */
    public static final class State {
        public final UUID uuid;
        public final String playerName;
        public int originDim;
        /** 放逐前的重生维度（原版 Integer，可能为 null 表示未设置） */
        public Integer originSpawnDim;
        public double ox, oy, oz;
        public float yaw, pitch;
        public GameType originGameType;
        /** 原世界背包（内存对象数组：36 主背包 + 4 护甲 + 1 副手），每次变动同步写文件 */
        public final ItemStack[] homeMain = newEmpty(36);
        public final ItemStack[] homeArmor = newEmpty(4);
        public final ItemStack[] homeOff = newEmpty(1);
        /** 放逐维度背包（初始为空；始终由玩家身体/player.dat 原版机制携带，不落我们的文件） */
        public final ItemStack[] banishMain = newEmpty(36);
        public final ItemStack[] banishArmor = newEmpty(4);
        public final ItemStack[] banishOff = newEmpty(1);
        /** /unbanish 或关服解除进行中：离开 114 时走"彻底解除"分支而非越狱拦截 */
        public boolean releasing = false;
        /** true = 本状态由 banishment/ 文件夹载入（重启后首次对调走"保留快照"合并，防回档丢包） */
        public boolean fromDisk = false;
        /** true = 管理员对离线玩家执行了 /unbanish：下次登录时执行解除（含从 114 送回原位） */
        public boolean pendingRelease = false;
        /** 最近一次成功落盘的时间戳（毫秒），仅用于诊断/列表展示 */
        public long savedAt = 0L;
        /**
         * 定时放逐的剩余「在线时长」（毫秒）。
         * >0 = 定时放逐：只在该玩家在线时倒计时，下线冻结、写进文件，
         *     重新上线续走，归零自动解除放逐。
         * <=0 = 无限期放逐（/banish 默认），由管理员用 /unbanish 解除。
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

    /** 非放逐玩家进入 114 前所处的维度与坐标（进入前的原位）。 */
    public static final class EntryPos {
        public final int dim;
        public final double x, y, z;
        public final float yaw, pitch;

        EntryPos(int dim, double x, double y, double z, float yaw, float pitch) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static ItemStack[] newEmpty(int n) {
        ItemStack[] a = new ItemStack[n];
        for (int i = 0; i < n; i++) a[i] = ItemStack.EMPTY;
        return a;
    }

    public static State get(EntityPlayer player) {
        return STATES.get(player.getUniqueID());
    }

    public static boolean isBanished(EntityPlayer player) {
        return STATES.containsKey(player.getUniqueID());
    }

    public static int countBanished() {
        return STATES.size();
    }

    /** 当前所有被放逐玩家名（含离线的）。 */
    public static List<String> banishedNames() {
        List<String> names = new ArrayList<String>();
        for (State s : STATES.values()) names.add(s.playerName);
        return names;
    }

    /** 取维度世界，未加载则尝试初始化（注册维度在开服时已随主世界一起创建，这里只是兜底）。 */
    private static WorldServer worldOrInit(int dim) {
        WorldServer w = DimensionManager.getWorld(dim);
        if (w == null) {
            try {
                DimensionManager.initDimension(dim);
            } catch (Exception ignored) {
                // 初始化失败时下面返回 null，由调用方兜底
            }
            w = DimensionManager.getWorld(dim);
        }
        return w;
    }

    // ------------------------------------------------------------------
    // 背包对调（原版对象级操作，零 NBT 序列化）
    // ------------------------------------------------------------------

    /**
     * 把玩家当前背包逐个 copy() 快照进目标数组，然后清空玩家背包。
     * keepMissing=true 用于"崩溃重启后的第一次对调"：身上对应槽位是空的
     * （player.dat 回档到了换装之后）时保留文件里已有的快照，防止旧档把新档抹掉。
     */
    private static void snapshotInto(EntityPlayer player, ItemStack[] main, ItemStack[] armor,
                                     ItemStack[] off, boolean keepMissing) {
        for (int i = 0; i < main.length; i++) {
            ItemStack held = player.inventory.mainInventory.get(i);
            if (!held.isEmpty()) {
                main[i] = held.copy();
            } else if (!keepMissing) {
                main[i] = ItemStack.EMPTY;
            }
        }
        for (int i = 0; i < armor.length; i++) {
            ItemStack held = player.inventory.armorInventory.get(i);
            if (!held.isEmpty()) {
                armor[i] = held.copy();
            } else if (!keepMissing) {
                armor[i] = ItemStack.EMPTY;
            }
        }
        ItemStack heldOff = player.inventory.offHandInventory.get(0);
        if (!heldOff.isEmpty()) {
            off[0] = heldOff.copy();
        } else if (!keepMissing) {
            off[0] = ItemStack.EMPTY;
        }
        player.inventory.clear();
        player.inventory.currentItem = 0;
        player.inventory.markDirty();
    }

    /** 把快照数组写回玩家背包。 */
    private static void applyFrom(EntityPlayer player, ItemStack[] main, ItemStack[] armor, ItemStack[] off) {
        player.inventory.clear();
        for (int i = 0; i < main.length && i < player.inventory.mainInventory.size(); i++) {
            player.inventory.mainInventory.set(i, main[i] == null ? ItemStack.EMPTY : main[i]);
        }
        for (int i = 0; i < armor.length && i < player.inventory.armorInventory.size(); i++) {
            player.inventory.armorInventory.set(i, armor[i] == null ? ItemStack.EMPTY : armor[i]);
        }
        player.inventory.offHandInventory.set(0, off[0] == null ? ItemStack.EMPTY : off[0]);
        player.inventory.currentItem = 0;
        player.inventory.markDirty();
        if (player instanceof EntityPlayerMP) {
            ((EntityPlayerMP) player).updateHeldItem();
        }
    }

    // ------------------------------------------------------------------
    // 放逐 / 解除 / 拉回
    // ------------------------------------------------------------------

    /** 按玩家名查放逐状态（离线解除用：记录文件里存了名字）。 */
    public static State findByName(String name) {
        for (State s : STATES.values()) {
            if (s.playerName.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    /** 全部放逐状态快照（/banish list 用）。 */
    public static List<State> banishedStates() {
        return new ArrayList<State>(STATES.values());
    }

    /** 离线玩家被 /unbanish：挂"待解除"标记并落盘，他下次登录时自动走完解除流程。 */
    public static void markPendingRelease(MinecraftServer server, State s) {
        s.pendingRelease = true;
        BanishmentStorage.save(server, s);
    }

    /** 带"离线待解除"标记的玩家登录：立即执行解除。 */
    public static void releaseOfflinePending(EntityPlayerMP mp) {
        State s = get(mp);
        if (s == null) return;
        s.pendingRelease = false;
        if (mp.dimension == DIM_ID) {
            release(mp, L10n.ACTOR_ADMIN);
        } else {
            // 崩溃回档把他留在了外面：先把身上物品并入快照（身上优先、空槽保留文件版），再原地收尾
            snapshotInto(mp, s.homeMain, s.homeArmor, s.homeOff, true);
            finishRelease(mp, s, L10n.ACTOR_ADMIN, false);
        }
    }

    /** 执行放逐：登记状态后传送进 114，背包对调/冒险模式/增益由维度切换事件统一完成。 */
    public static void banish(EntityPlayerMP target, String by) {
        performBanish(target, by);
    }

    /** /banish 共用的传送主体。 */
    private static void performBanish(EntityPlayerMP target, String by) {
        if (target.dimension == DIM_ID) {
            target.sendMessage(L10n.bi("msg.err.already_in_dim"));
            return;
        }
        WorldServer dest = worldOrInit(DIM_ID);
        if (dest == null) {
            target.sendMessage(L10n.bi("msg.err.dim_not_loaded"));
            return;
        }

        State s = new State(target.getUniqueID(), target.getName());
        s.originDim = target.dimension;
        s.originSpawnDim = target.getSpawnDimension();
        s.ox = target.posX;
        s.oy = target.posY;
        s.oz = target.posZ;
        s.yaw = target.rotationYaw;
        s.pitch = target.rotationPitch;
        s.originGameType = target.interactionManager.getGameType();
        STATES.put(s.uuid, s);
        // 被放逐者不再走 /escape 的原位逻辑，顺手清掉可能存在的误入原位记录
        ENTRY_POSITIONS.remove(target.getUniqueID());
        // 先落一份初始记录：哪怕传送当刻崩溃，重启后也能凭文件把放逐续上
        BanishmentStorage.save(currentServer(), s);

        target.closeScreen();
        target.sendMessage(L10n.bi("msg.notice.banished", by == null ? L10n.ACTOR_SYSTEM : by));
        playTeleportSound(target);
        target.changeDimension(DIM_ID, new FixedTeleporter(dest, SAFE_X, SAFE_Y, SAFE_Z, 0.0F, 0.0F));
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
        EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(s.uuid);
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
        EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(s.uuid);
        if (mp != null) {
            mp.sendMessage(L10n.bi("msg.notice.banishtime_set", String.valueOf(minutes),
                    String.valueOf(remainingMinutes(s))));
        }
    }

    /**
     * 定时放逐到期自动解除：与 /unbanish 相同地把玩家送回原位并还原背包/游戏模式，
     * 但给被放逐者的通知改为「放逐已到期」（releaseNotice），并在控制台留一行日志。
     */
    public static void releaseTimedExpired(EntityPlayerMP player) {
        State s = get(player);
        if (s == null) return;
        s.banishtimeRemainingMs = 0L;
        s.releaseNotice = "msg.notice.expired";
        L10n.log("log.banishtime_expired", s.playerName);
        player.closeScreen();
        playTeleportSound(player);
        if (player.dimension == DIM_ID) {
            WorldServer dest = worldOrInit(s.originDim);
            if (dest == null) {
                // 原维度竟不可用：就地物品交换收尾，把原背包掉在脚下，避免丢东西
                fallbackRelease(player, s, dest);
                return;
            }
            s.releasing = true;
            player.changeDimension(s.originDim, new FixedTeleporter(dest, s.ox, s.oy, s.oz, s.yaw, s.pitch));
            // 维度切换事件里走 finishRelease（dropCarried=true：放逐背包里的遗留物掉在回归点）
        } else {
            // 越狱窗口中：身上的就是原背包，直接收尾
            finishRelease(player, s, L10n.ACTOR_SYSTEM, false);
        }
    }

    /** 进入放逐维度时（含放逐、重启续放、越狱拉回）统一执行的动作。 */
    public static void onEnterDimension(EntityPlayer player) {
        State s = get(player);
        if (s == null) return;
        // 当前身上的（原世界 + 越狱期间捡到的）全部物品收进"原背包"槽，换回放逐背包；
        // 重启后首次进入时按"身上优先、空槽保留文件快照"合并，防回档清空文件版背包
        boolean mergeFromDisk = s.fromDisk;
        snapshotInto(player, s.homeMain, s.homeArmor, s.homeOff, mergeFromDisk);
        applyFrom(player, s.banishMain, s.banishArmor, s.banishOff);
        s.fromDisk = false;
        if (player instanceof EntityPlayerMP) {
            EntityPlayerMP mp = (EntityPlayerMP) player;
            mp.setGameType(GameType.ADVENTURE);
            mp.setSpawnPoint(SAFE_POS, true);
            mp.setSpawnDimension(DIM_ID);
        }
        refreshEffects(player);
        BanishmentStorage.save(currentServer(), s);
    }

    /** 离开 114 但不是解除（越狱窗口）：放逐背包入内存，把原背包还给玩家，再立刻拉回。 */
    public static void onLeaveDimension(EntityPlayer player) {
        State s = get(player);
        if (s == null) return;
        snapshotInto(player, s.banishMain, s.banishArmor, s.banishOff, false);
        applyFrom(player, s.homeMain, s.homeArmor, s.homeOff);
    }

    private static MinecraftServer currentServer() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    /** 解除放逐（/unbanish 使用）。 */
    public static void release(EntityPlayerMP player, String by) {
        State s = get(player);
        if (s == null) return;
        player.closeScreen();
        playTeleportSound(player);
        if (player.dimension == DIM_ID) {
            WorldServer dest = worldOrInit(s.originDim);
            if (dest == null) {
                // 原维度竟不可用：就地物品交换收尾，把原背包掉在脚下，避免丢东西
                fallbackRelease(player, s, dest);
                return;
            }
            s.releasing = true;
            player.changeDimension(s.originDim, new FixedTeleporter(dest, s.ox, s.oy, s.oz, s.yaw, s.pitch));
            // 维度切换事件里走 finishRelease（dropCarried=true：放逐背包里的遗留物掉在回归点）
        } else {
            // 越狱窗口中：身上的就是原背包，直接收尾
            finishRelease(player, s, by, false);
        }
    }

    /** 彻底收尾：还原背包、游戏模式、重生点与重生维度，清空放逐增益，销账。 */
    public static void finishRelease(EntityPlayer player, State s, String by, boolean dropCarried) {
        if (dropCarried) {
            // 放逐维度背包里若有任何东西（如管理员投喂）：掉在回归点地面，不走 NBT 保存
            for (ItemStack st : player.inventory.mainInventory) dropIfPresent(player, st);
            for (ItemStack st : player.inventory.armorInventory) dropIfPresent(player, st);
            dropIfPresent(player, player.inventory.offHandInventory.get(0));
        }
        applyFrom(player, s.homeMain, s.homeArmor, s.homeOff);
        if (player instanceof EntityPlayerMP) {
            EntityPlayerMP mp = (EntityPlayerMP) player;
            mp.setGameType(s.originGameType);
            mp.setSpawnPoint(new BlockPos(MathHelper.floor(s.ox), MathHelper.floor(s.oy),
                    MathHelper.floor(s.oz)), false);
            mp.setSpawnDimension(s.originSpawnDim);
        }
        removeEffects(player);
        STATES.remove(s.uuid);
        BanishmentStorage.delete(currentServer(), s.uuid);
        playTeleportSound(player);
        player.sendMessage(L10n.bi(s.releaseNotice, by == null ? L10n.ACTOR_SYSTEM : by));
    }

    /** 反越狱拉回：立即传送回 114 并刷新状态循环。 */
    public static void pullback(EntityPlayerMP player) {
        State s = get(player);
        if (s == null) return;
        WorldServer dest = worldOrInit(DIM_ID);
        if (dest == null) {
            // 放逐维度本身没加载：按自动解除处理，避免无限拉回死循环
            player.sendMessage(L10n.bi("msg.warn.dim_unavailable"));
            finishRelease(player, s, L10n.ACTOR_SYSTEM, false);
            return;
        }
        player.sendMessage(L10n.bi("msg.warn.pullback"));
        playTeleportSound(player);
        player.changeDimension(DIM_ID, new FixedTeleporter(dest, SAFE_X, SAFE_Y, SAFE_Z, 0.0F, 0.0F));
    }

    /**
     * 把「未在放逐名单却混进 114」的玩家送回主世界出生点。
     * 用于维度切换/登录事件漏拦后的巡检兜底：不碰背包（这些人本就没有放逐记录），
     * 只保证他们永远能离开这个维度，不会被困死。
     */
    public static void ejectToOverworld(EntityPlayerMP player) {
        WorldServer overworld = worldOrInit(0);
        if (overworld == null) {
            // 主世界都加载不出来：本次作罢，等下一个 10 秒巡检再试（不会因此困死，只是多等几秒）
            player.sendMessage(L10n.bi("msg.err.overworld_unavailable"));
            return;
        }
        BlockPos sp = overworld.getTopSolidOrLiquidBlock(overworld.getSpawnPoint());
        player.sendMessage(L10n.bi("msg.notice.not_your_place"));
        playTeleportSound(player);
        player.changeDimension(0, new FixedTeleporter(overworld,
                sp.getX() + 0.5D, sp.getY() + 1.0D, sp.getZ() + 0.5D, player.rotationYaw, player.rotationPitch));
    }

    /**
     * 记录「非放逐玩家」进入 114 瞬间之前所处的原位。
     * 在 EntityTravelToDimensionEvent（维度切换前触发）里调用，此时玩家的坐标仍是原维度里的坐标。
     * 被放逐玩家不记录（/escape 只对非放逐者生效，没有记录本来就不该拿）。
     */
    public static void recordEntryPosition(EntityPlayerMP player) {
        if (player.dimension == DIM_ID) return; // 已在 114，不算「进入前」
        ENTRY_POSITIONS.put(player.getUniqueID(), new EntryPos(
                player.dimension, player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch));
    }

    /**
     * 把「未在放逐名单却待在 114」的玩家送回其进入 114 之前的位置（原位）。
     * 送回后清除本次原位记录，避免下次误用时把玩家丢到旧坐标。
     * - 有原位记录且原维度可加载：精确送回到进入前的坐标；
     * - 无原位记录或原维度暂不可用：退回主世界出生点兜底（见 ejectToOverworld）。
     * 不碰背包（这些人本就没有放逐记录），只保证他们能离开这个维度，不会被困死。
     */
    public static void returnNonBanishedToOrigin(EntityPlayerMP player) {
        EntryPos p = ENTRY_POSITIONS.remove(player.getUniqueID());
        if (p != null) {
            WorldServer dest = worldOrInit(p.dim);
            if (dest != null) {
                player.sendMessage(L10n.bi("msg.notice.returned_origin"));
                playTeleportSound(player);
                if (player.dimension == p.dim) {
                    // 原点维度即当前维度：就地放置，不走跨维度传送
                    if (player.connection != null) {
                        player.connection.setPlayerLocation(p.x, p.y, p.z, p.yaw, p.pitch);
                    }
                } else {
                    player.changeDimension(p.dim, new FixedTeleporter(dest, p.x, p.y, p.z, p.yaw, p.pitch));
                }
                return;
            }
        }
        ejectToOverworld(player);
    }

    /** 强制把指定在线玩家送回主世界（OP 指令 /return 用）。
     * - 若 TA 在放逐名单里：完整解除放逐（还原原背包/游戏模式/重生点、清增益、删记录），
     *   落点定为主世界出生点，避免被巡检当作越狱再拉回。
     * - 若 TA 只是越权闯入（不在名单）：直接送回主世界，身上物品随行，不没收。
     * - 主世界不可用时不乱传，报错并等待下次，绝不把玩家丢进虚空。
     */
    public static void forceReturn(EntityPlayerMP player, String by) {
        player.closeScreen();
        playTeleportSound(player);
        WorldServer overworld = worldOrInit(0);
        if (overworld == null) {
            player.sendMessage(L10n.bi("msg.err.overworld_unavailable"));
            return;
        }
        BlockPos sp = overworld.getTopSolidOrLiquidBlock(overworld.getSpawnPoint());
        double rx = sp.getX() + 0.5D, ry = sp.getY() + 1.0D, rz = sp.getZ() + 0.5D;
        State s = get(player);
        List<ItemStack> carried = null;
        if (s != null) {
            // 先把放逐背包（身上当前物品）收集起来，落地时掉在主世界，保证零蒸发
            carried = collectCarried(player);
            // 被放逐者：先完整解除（releasing=true 让"离开 114"事件走正式解除分支而非越狱拉回）
            s.releasing = true;
            finishRelease(player, s, by, false);
        } else {
            player.sendMessage(L10n.bi("msg.notice.returned", by));
        }
        // 直接落到主世界出生点；玩家已在主世界则就地放置。
        if (player.dimension == 0) {
            if (player.connection != null) {
                player.connection.setPlayerLocation(rx, ry, rz, player.rotationYaw, player.rotationPitch);
            }
        } else {
            player.changeDimension(0, new FixedTeleporter(overworld, rx, ry, rz,
                    player.rotationYaw, player.rotationPitch));
        }
        // 放逐背包里若有东西（如管理员投喂）：掉在落地脚边，交由玩家自行拾取
        if (carried != null) {
            for (ItemStack st : carried) {
                if (st != null && !st.isEmpty()) spawnItemAt(overworld, player, st);
            }
        }
    }

    /** 收集玩家当前身上的全部物品副本（主背包 + 护甲 + 副手）。 */
    private static List<ItemStack> collectCarried(EntityPlayer player) {
        List<ItemStack> list = new ArrayList<ItemStack>();
        for (ItemStack st : player.inventory.mainInventory) {
            if (st != null && !st.isEmpty()) list.add(st.copy());
        }
        for (ItemStack st : player.inventory.armorInventory) {
            if (st != null && !st.isEmpty()) list.add(st.copy());
        }
        ItemStack off = player.inventory.offHandInventory.get(0);
        if (off != null && !off.isEmpty()) list.add(off.copy());
        return list;
    }

    /** 极端兜底：解除时原维度世界不可用。放逐背包与"活"物品都就地处理，绝不写 NBT。 */
    private static void fallbackRelease(EntityPlayerMP player, State s, WorldServer unusedDest) {
        World cur = player.getEntityWorld();
        for (ItemStack st : s.homeMain) spawnItemAt(cur, player, st);
        for (ItemStack st : s.homeArmor) spawnItemAt(cur, player, st);
        spawnItemAt(cur, player, s.homeOff[0]);
        player.inventory.clear();
        player.setGameType(s.originGameType);
        player.setSpawnDimension(s.originSpawnDim);
        removeEffects(player);
        STATES.remove(s.uuid);
        BanishmentStorage.delete(currentServer(), s.uuid);
        player.sendMessage(L10n.bi("msg.warn.fallback_drop"));
    }

    // ------------------------------------------------------------------
    // 状态循环（10 秒）
    // ------------------------------------------------------------------

    /** 刷新放逐四件套：挖掘疲劳 V / 抗性提升 V / 抗火 / 饱和 X，各持续 200 刻，无粒子。 */
    public static void refreshEffects(EntityPlayer player) {
        player.addPotionEffect(new PotionEffect(MobEffects.MINING_FATIGUE, CYCLE_TICKS, 4, true, false));
        player.addPotionEffect(new PotionEffect(MobEffects.RESISTANCE, CYCLE_TICKS, 4, true, false));
        player.addPotionEffect(new PotionEffect(MobEffects.FIRE_RESISTANCE, CYCLE_TICKS, 0, true, false));
        player.addPotionEffect(new PotionEffect(MobEffects.SATURATION, CYCLE_TICKS, 9, true, false));
    }

    /** 清空放逐增益（解除时调用）。 */
    public static void removeEffects(EntityPlayer player) {
        player.removePotionEffect(MobEffects.MINING_FATIGUE);
        player.removePotionEffect(MobEffects.RESISTANCE);
        player.removePotionEffect(MobEffects.FIRE_RESISTANCE);
        player.removePotionEffect(MobEffects.SATURATION);
    }

    // ------------------------------------------------------------------
    // 巡检（每 200 刻）
    // ------------------------------------------------------------------

    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        boolean sweep = (tickCounter % CYCLE_TICKS == 0L);
        for (Object obj : server.getPlayerList().getPlayers()) {
            EntityPlayerMP mp = (EntityPlayerMP) obj;
            State s = get(mp);
            if (s == null) {
                // 未在放逐名单却待在 114（越权进入 / 记录文件被删 / 崩溃遗留等）：
                // 维度切换或登录事件一旦漏掉，这里每 10 秒兜底一次，尽量送回进入前原位，
                // 没记录或原维度不可用则退回主世界出生点，绝不让玩家困死。
                if (sweep && mp.dimension == DIM_ID) {
                    returnNonBanishedToOrigin(mp);
                }
                continue;
            }
            if (s.releasing) continue;

            // 定时放逐：只在在线时按真实流逝时间倒计时，下线冻结；归零即到期自动解除。
            if (s.banishtimeRemainingMs > 0L) {
                long now = System.currentTimeMillis();
                if (s.banishtimeLastMs == 0L) {
                    // 刚上线/刚定时：从当下起算，绝不把离线时间算进去
                    s.banishtimeLastMs = now;
                } else {
                    long delta = now - s.banishtimeLastMs;
                    s.banishtimeLastMs = now;
                    if (delta > 0L) {
                        s.banishtimeRemainingMs -= delta;
                    }
                }
                if (s.banishtimeRemainingMs <= 0L) {
                    releaseTimedExpired(mp);
                    continue;
                }
            }

            if (mp.dimension != DIM_ID) {
                pullback(mp);
            } else {
                refreshEffects(mp);
                // 兜底落盘：把最新快照同步进 banishment/ 文件夹，
                // 崩溃最多回档到上一次保存点，绝不丢记录。每 tick 写一次能让
                // 定时放逐的剩余时长尽可能接近真实值。
                BanishmentStorage.save(server, s);
            }
        }
        // 每 10 秒同步清场：放逐维度只允许玩家和掉落物存在，
        // 把爆炸、越狱机制、通道里溜进来的其它实体全部移除，保证被放逐者只能独处。
        if (sweep) {
            clearEntities(DimensionManager.getWorld(DIM_ID));
        }
    }

    /**
     * 放逐维度清场：移除除玩家与掉落物之外的所有实体。
     * 放逐维度虽然禁刷怪（见 BanishmentEvents 的 spawn 拦截），但爆炸、投喂、
     * 越狱折返、玩家带入的载具/盔甲架等仍可能把其它实体带进来，这里每 10 秒兜底扫一遍。
     * 玩家（EntityPlayer，含 EntityPlayerMP）和掉落物（EntityItem）保留，其余一律移除。
     * 先快照再处理，避免遍历实况列表时被改动。
     */
    public static void clearEntities(WorldServer world) {
        if (world == null) return;
        List<Entity> snapshot = new ArrayList<Entity>();
        for (Object obj : world.loadedEntityList) {
            if (obj instanceof Entity) snapshot.add((Entity) obj);
        }
        for (Entity e : snapshot) {
            if (e instanceof EntityPlayer || e instanceof EntityItem) continue;
            world.removeEntity(e);
        }
    }

    /**
     * 关服收尾（持久化版）：不再自动解除放逐，而是把全部记录写进服务端存档的
     * banishment/ 文件夹后清空内存。下次开服 loadAll 自动续放——在线玩家会留在
     * 114（其放逐维度背包由原版 player.dat 正常保存），离线玩家的背包快照也在文件里，
     * 物品零丢失、放逐零蒸发。
     */
    public static void persistAllOnStop(MinecraftServer server) {
        if (STATES.isEmpty()) return;
        for (State s : new ArrayList<State>(STATES.values())) {
            BanishmentStorage.save(server, s);
        }
        L10n.log("log.server_stopping", String.valueOf(STATES.size()),
                BanishmentStorage.storageDir(server).getName());
        STATES.clear();
    }

    private static void spawnItemAt(World world, EntityPlayer at, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        EntityItem item = new EntityItem(world, at.posX, at.posY, at.posZ, stack);
        item.setPickupDelay(60);
        world.spawnEntity(item);
    }

    private static void dropIfPresent(EntityPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        EntityItem item = new EntityItem(player.getEntityWorld(), player.posX, player.posY, player.posZ, stack.copy());
        item.setPickupDelay(60);
        player.getEntityWorld().spawnEntity(item);
    }

    private static void playTeleportSound(EntityPlayer player) {
        try {
            player.getEntityWorld().playSound((EntityPlayer) null, player.posX, player.posY, player.posZ,
                    SoundEvents.ENTITY_ENDERMEN_TELEPORT, SoundCategory.PLAYERS, 1.0F, 0.8F);
        } catch (Exception ignored) {
            // 音频失败不影响放逐流程
        }
    }
}
