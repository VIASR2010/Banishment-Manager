package cn.blockforge.generated.banishmentdimension;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

/**
 * 服务端文件夹版放逐记录存储。作者：VIASR。
 *
 * 位置：世界存档根目录下的 banishment/ 文件夹（例如 world/banishment/&lt;玩家UUID&gt;.json）。
 * 格式：纯文本 JSON（Gson），一个被放逐玩家一个文件，管理员可以直接用记事本打开查看。
 *
 * 全程零 NBT：
 * - 文件内容是人眼可读的 JSON 文本，不是 NBT 二进制；
 * - 物品只记三样东西——注册名、耐久/变体值(meta)、数量，形如 "minecraft:diamond_sword|0|1"，
 *   不碰 ItemStack 的 tag 标签，对任何自定义物品模组零侵入；
 *   （代价：附魔、药水自定义效果、改名等标签信息不保留，保存时会在控制台提示一次。）
 *
 * 重启续放逐：开服时 loadAll() 把整个文件夹读回内存，关服/登出/换袋时自动落盘，
 * 崩溃重启后放逐状态与原背包快照依然在（快照粒度 = 最近一次保存点）。
 */
public final class BanishmentStorage {

    /** 存档根目录下的放逐数据文件夹名 */
    public static final String FOLDER = "banishment";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 带标签物品只提示一次/玩家会话，避免每 10 秒自动保存刷屏 */
    private static final Set<UUID> NBT_WARNED = new HashSet<UUID>();

    private BanishmentStorage() {}

    /** 落盘的纯文本记录。物品槽位为 "注册名|meta|数量" 字符串，空格是 null。 */
    public static final class Record {
        public String player;
        public String uuid;
        public long savedAt;
        public int originDim;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        /** GameType.ID（原版数值，跨版本可读性好） */
        public int gameType;
        /** 放逐前的重生维度，可为 null（未设置） */
        public Integer spawnDim;
        /** true = 管理员已对离线 TA 执行 /unbanish，等 TA 上线执行 */
        public boolean pendingRelease;
        /** 定时放逐剩余在线时长（毫秒）。>0 = 定时放逐（在线计时、下线冻结）；<=0 = 无限期放逐。 */
        public long banishtimeRemainingMs;
        public String[] homeMain;
        public String[] homeArmor;
        public String homeOff;
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 取 banishment/ 文件夹（世界根目录下）。拿不到存档目录时退回运行目录。 */
    public static File storageDir(MinecraftServer server) {
        try {
            if (server != null) {
                WorldServer overworld = server.getWorld(0);
                if (overworld != null) {
                    File worldRoot = overworld.getSaveHandler().getWorldDirectory();
                    if (worldRoot != null) return new File(worldRoot, FOLDER);
                }
            }
        } catch (Throwable ignored) {
            // 兜底走运行目录
        }
        return new File(FOLDER);
    }

    private static File recordFile(File dir, UUID uuid) {
        return new File(dir, uuid.toString() + ".json");
    }

    // ------------------------------------------------------------------
    // 保存 / 删除
    // ------------------------------------------------------------------

    /** 把一个放逐状态写盘（原子写：先 .tmp 再替换），并刷新内存里的 savedAt。 */
    public static void save(MinecraftServer server, BanishmentManager.State s) {
        File dir = storageDir(server);
        Record r = toRecord(s);
        File target = recordFile(dir, s.uuid);
        File tmp = new File(dir, s.uuid.toString() + ".json.tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("无法创建文件夹 " + dir.getAbsolutePath());
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                GSON.toJson(r, w);
            } finally {
                w.close();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException first) {
                // 个别系统上替换失败：先删再移
                if (target.exists() && !target.delete()) {
                    throw first;
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            s.savedAt = r.savedAt;
        } catch (Exception e) {
            System.out.println("[放逐] 写入放逐记录失败（" + s.playerName + "）：" + e
                    + "。放逐仍在本会话内生效，但重启后会回退到上一次成功保存的状态。");
            if (tmp.exists() && !tmp.delete()) {
                // 临时文件删不掉就算了，下次写盘会覆盖
            }
        }
    }

    /** 解除放逐时删档。 */
    public static void delete(MinecraftServer server, UUID uuid) {
        File dir = storageDir(server);
        File f = recordFile(dir, uuid);
        if (f.exists() && !f.delete()) {
            System.out.println("[放逐] 放逐记录文件删除失败（可手动清理）：" + f.getAbsolutePath());
        }
        File tmp = new File(dir, uuid.toString() + ".json.tmp");
        if (tmp.exists()) {
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------
    // 开服加载
    // ------------------------------------------------------------------

    /** 开服时把 banishment/ 里所有记录读回内存，返回加载数量。坏文件挪成 .broken 不阻塞开服。 */
    public static int loadAll(MinecraftServer server) {
        File dir = storageDir(server);
        if (!dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int loaded = 0;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".json") || f.isDirectory()) continue;
            try {
                Record r;
                Reader rd = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
                try {
                    r = GSON.fromJson(rd, Record.class);
                } finally {
                    rd.close();
                }
                if (r == null || r.uuid == null) {
                    throw new IOException("空记录或缺少 uuid 字段");
                }
                UUID uuid = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                if (BanishmentManager.STATES.containsKey(uuid)) {
                    continue; // 内存优先，防重复
                }
                BanishmentManager.State s = fromRecord(uuid, r);
                BanishmentManager.STATES.put(uuid, s);
                loaded++;
            } catch (Exception e) {
                System.out.println("[放逐] 放逐记录损坏，已跳过并改名为 .broken：" + name + "（" + e + "）");
                f.renameTo(new File(f.getParentFile(), name + ".broken"));
            }
        }
        if (loaded > 0) {
            L10n.log("log.server_started", dir.getAbsolutePath(), String.valueOf(loaded));
        }
        return loaded;
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private static Record toRecord(BanishmentManager.State s) {
        Record r = new Record();
        r.player = s.playerName;
        r.uuid = s.uuid.toString();
        r.savedAt = System.currentTimeMillis();
        r.originDim = s.originDim;
        r.x = s.ox; r.y = s.oy; r.z = s.oz;
        r.yaw = s.yaw; r.pitch = s.pitch;
        r.gameType = s.originGameType == null ? GameType.SURVIVAL.getID() : s.originGameType.getID();
        r.spawnDim = s.originSpawnDim;
        r.pendingRelease = s.pendingRelease;
        r.banishtimeRemainingMs = s.banishtimeRemainingMs;

        List<String> tagged = new ArrayList<String>();
        r.homeMain = new String[s.homeMain.length];
        for (int i = 0; i < s.homeMain.length; i++) {
            r.homeMain[i] = encodeStack(s.homeMain[i], tagged);
        }
        r.homeArmor = new String[s.homeArmor.length];
        for (int i = 0; i < s.homeArmor.length; i++) {
            r.homeArmor[i] = encodeStack(s.homeArmor[i], tagged);
        }
        r.homeOff = encodeStack(s.homeOff[0], tagged);

        if (!tagged.isEmpty() && NBT_WARNED.add(s.uuid)) {
            System.out.println("[放逐] 提醒：" + s.playerName + " 的原背包里有 " + tagged.size()
                    + " 件带 NBT 标签的物品（附魔/药水/改名/潜影盒内容等）。按零 NBT 方案，文件里只保留"
                    + "物品本体+耐久+数量，标签信息在重启回档时不会恢复。涉及物品：" + preview(tagged));
        }
        return r;
    }

    private static BanishmentManager.State fromRecord(UUID uuid, Record r) {
        BanishmentManager.State s = new BanishmentManager.State(uuid, r.player == null ? "?" : r.player);
        s.originDim = r.originDim;
        s.ox = r.x; s.oy = r.y; s.oz = r.z;
        s.yaw = r.yaw; s.pitch = r.pitch;
        s.originGameType = GameType.getByID(r.gameType);
        if (s.originGameType == null || s.originGameType == GameType.NOT_SET) {
            s.originGameType = GameType.SURVIVAL;
        }
        s.originSpawnDim = r.spawnDim;
        s.fromDisk = true;
        s.pendingRelease = r.pendingRelease;
        s.banishtimeRemainingMs = r.banishtimeRemainingMs;
        s.banishtimeLastMs = 0L;
        s.savedAt = r.savedAt;
        s.releasing = false;
        if (r.homeMain != null) {
            for (int i = 0; i < s.homeMain.length && i < r.homeMain.length; i++) {
                s.homeMain[i] = decodeStack(r.player, "主背包" + i, r.homeMain[i]);
            }
        }
        if (r.homeArmor != null) {
            for (int i = 0; i < s.homeArmor.length && i < r.homeArmor.length; i++) {
                s.homeArmor[i] = decodeStack(r.player, "护甲" + i, r.homeArmor[i]);
            }
        }
        s.homeOff[0] = decodeStack(r.player, "副手", r.homeOff);
        return s;
    }

    /** 物品 → "注册名|meta|数量"；空格返回 null。带 tag 的记入提醒清单但只存本体。 */
    private static String encodeStack(ItemStack st, List<String> taggedOut) {
        if (st == null || st.isEmpty()) return null;
        if (st.hasTagCompound() && !st.getTagCompound().isEmpty() && taggedOut != null) {
            ResourceLocation warnId = (ResourceLocation) Item.REGISTRY.getNameForObject(st.getItem());
            taggedOut.add(String.valueOf(warnId));
        }
        ResourceLocation id = (ResourceLocation) Item.REGISTRY.getNameForObject(st.getItem());
        if (id == null) return null; // 理论上不会发生：物品不在注册表
        return id.toString() + "|" + st.getMetadata() + "|" + st.getCount();
    }

    /** "注册名|meta|数量" → 活的原版 ItemStack；解析失败/模组被删则跳过并告警。 */
    private static ItemStack decodeStack(String owner, String slotDesc, String enc) {
        if (enc == null || enc.isEmpty()) return ItemStack.EMPTY;
        String[] p = enc.split("\\|");
        if (p.length != 3) {
            System.out.println("[放逐] " + owner + " 的" + slotDesc + "槽位格式异常，已跳过：" + enc);
            return ItemStack.EMPTY;
        }
        Item item = Item.REGISTRY.getObject(new ResourceLocation(p[0]));
        if (item == null) {
            System.out.println("[放逐] " + owner + " 的" + slotDesc + "里的物品 " + p[0]
                    + " 已不存在（对应模组可能被移除），已跳过。");
            return ItemStack.EMPTY;
        }
        try {
            int meta = Integer.parseInt(p[1]);
            int count = Math.max(1, Integer.parseInt(p[2]));
            return new ItemStack(item, count, meta);
        } catch (NumberFormatException e) {
            System.out.println("[放逐] " + owner + " 的" + slotDesc + "槽位数值异常，已跳过：" + enc);
            return ItemStack.EMPTY;
        }
    }

    private static String preview(List<String> list) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), 6);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        if (list.size() > n) sb.append(" 等");
        return sb.toString();
    }
}
