package cn.blockforge.generated.banishmentdimension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/**
 * 放逐记录存储（服务端存档版）。作者：VIASR，r20 行为等价重写。
 *
 * 位置不变：世界存档根目录下的 banishment 文件夹，一个被放逐玩家一个 JSON 文件，
 * r19 的旧记录直接可读（旧世界、旧存档继续用）。格式仍是管理员用记事本就能看的
 * 纯文本 JSON，默认仍坚持「零 NBT」的设计语言：
 *
 *   plain 编码：槽位记 "注册名|数量" 两段（旧版 "注册名|变体|数量" 三段也照读，
 *               变体位仅记日志、不回写——新版物品变体已并入组件）。
 *   enhanced 编码：槽位记完整组件 JSON（附魔、耐久等一起保），想升级编码改配置即可。
 *
 * 维度引用从旧版的整数 ID 升级为字符串 ID（minecraft:overworld 这类）；
 * 读 r19 旧档时 -1、0、1 自动映射到 下界、主世界、末地，其余按主世界处理并提示。
 * 全程原子写：先 .tmp 再替换。开服 loadAll、变动即 save、解除 delete——
 * 关服、崩溃、重启后放逐状态都在（背包快照粒度 = 最近一次保存点）。
 */
public final class BanishmentStorage {

    /** 存档根目录下的放逐数据文件夹名（与 r19 一致）。 */
    public static final String FOLDER = "banishment";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 带组件的物品被纯文本编码丢信息时，每玩家只提示一次，避免每 10 秒刷屏。 */
    private static final Set<UUID> COMPONENT_WARNED = new HashSet<UUID>();

    private BanishmentStorage() {}

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 取存档根目录下的 banishment 文件夹。拿不到存档目录时退回运行目录。 */
    public static Path storageDir(MinecraftServer server) {
        try {
            if (server != null) {
                return server.getSavePath(WorldSavePath.ROOT).resolve(FOLDER);
            }
        } catch (Throwable ignored) {
            // 兜底走运行目录
        }
        return FabricLoader.getInstance().getGameDir().resolve(FOLDER);
    }

    private static Path recordFile(Path dir, UUID uuid) {
        return dir.resolve(uuid.toString() + ".json");
    }

    // ------------------------------------------------------------------
    // 保存 / 删除
    // ------------------------------------------------------------------

    /** 把一个放逐状态写盘（原子写：先 .tmp 再替换），并刷新内存里的 savedAt。 */
    public static void save(MinecraftServer server, BanishmentManager.State s) {
        Path dir = storageDir(server);
        JsonObject o = toJson(server, s);
        String text = GSON.toJson(o);
        try {
            Files.createDirectories(dir);
            Path target = recordFile(dir, s.uuid);
            Path tmp = dir.resolve(s.uuid.toString() + ".json.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            s.savedAt = System.currentTimeMillis();
        } catch (IOException e) {
            L10n.warn("放逐记录写盘失败（" + s.playerName + "）/ failed to write record: " + e);
        }
    }

    /** 解除放逐时删档。 */
    public static void delete(MinecraftServer server, UUID uuid) {
        try {
            Files.deleteIfExists(recordFile(storageDir(server), uuid));
        } catch (IOException e) {
            L10n.warn("放逐记录删除失败 " + uuid + " / failed to delete: " + e);
        }
    }

    // ------------------------------------------------------------------
    // 载入
    // ------------------------------------------------------------------

    /** 开服时把 banishment 文件夹里的记录全部读回内存。返回读到的条数。 */
    public static int loadAll(MinecraftServer server) {
        Path dir = storageDir(server);
        if (!Files.isDirectory(dir)) return 0;
        List<Path> files = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".json")) files.add(p);
            });
        } catch (IOException e) {
            L10n.warn("放逐记录目录读取失败 / failed to list records: " + e);
            return 0;
        }
        int count = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            UUID id = parseUuid(name.substring(0, name.length() - 5));
            if (id == null) continue; // 不是玩家记录文件（比如 .broken 备份）
            if (BanishmentManager.STATES.containsKey(id)) {
                try { Files.deleteIfExists(file); } catch (IOException ignored2) {}
                continue;
            }
            BanishmentManager.State s = null;
            try {
                s = fromJson(server, Files.readString(file, StandardCharsets.UTF_8), id);
            } catch (Exception e) {
                L10n.warn("放逐记录损坏，已改名留档 " + name + " / corrupt record, kept as .broken: " + e);
                try {
                    Files.move(file, file.resolveSibling(name + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored2) {}
                continue;
            }
            if (s == null) {
                try { Files.deleteIfExists(file); } catch (IOException ignored2) {}
                continue;
            }
            s.fromDisk = true; // 重启后首次对调走「保留快照」合并，防回档丢包
            BanishmentManager.STATES.put(s.uuid, s);
            count++;
        }
        return count;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 序列化（JsonObject 手工拼装：槽位可能是字符串或对象，两种都要能读）
    // ------------------------------------------------------------------

    private static JsonObject toJson(MinecraftServer server, BanishmentManager.State s) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 2);
        o.addProperty("player", s.playerName);
        o.addProperty("uuid", s.uuid.toString());
        o.addProperty("savedAt", s.savedAt);
        o.addProperty("originDimension", s.originDim == null ? "" : s.originDim.getValue().toString());
        o.addProperty("x", s.ox);
        o.addProperty("y", s.oy);
        o.addProperty("z", s.oz);
        o.addProperty("yaw", s.yaw);
        o.addProperty("pitch", s.pitch);
        o.addProperty("gameType", GameMode.getId(s.originGameType));
        if (s.originSpawnDim != null) {
            o.addProperty("spawnDimension", s.originSpawnDim.getValue().toString());
        }
        if (s.originSpawnPos != null) {
            JsonArray pos = new JsonArray();
            pos.add(s.originSpawnPos.getX());
            pos.add(s.originSpawnPos.getY());
            pos.add(s.originSpawnPos.getZ());
            o.add("spawnPos", pos);
        }
        o.addProperty("spawnAngle", s.originSpawnAngle);
        o.addProperty("spawnForced", s.originSpawnForced);
        o.addProperty("pendingRelease", s.pendingRelease);
        // 定时放逐剩余「在线时长」（毫秒），0 = 无限期。只存剩余量，不存计时起点：
        // 起点属于运行时状态，重启/重连后一律重新起算（见 onJoin 的 banishtimeLastMs = 0）。
        if (s.banishtimeRemainingMs > 0L) {
            o.addProperty("banishtimeRemainingMs", s.banishtimeRemainingMs);
        }

        boolean enhanced = ModConfig.ENC_ENHANCED.equals(ModConfig.itemEncoding);
        RegistryOps<JsonElement> ops = enhanced && server != null
                ? asJsonOps(server) : null;

        JsonArray main = new JsonArray();
        int tagged = 0;
        for (int i = 0; i < s.homeMain.length; i++) {
            JsonElement el = encodeStack(s.homeMain[i], enhanced, ops);
            main.add(el);
            if (el instanceof JsonPrimitive) tagged += hasComponents(s.homeMain[i]) ? 1 : 0;
        }
        JsonArray armor = new JsonArray();
        for (int i = 0; i < s.homeArmor.length; i++) {
            JsonElement el = encodeStack(s.homeArmor[i], enhanced, ops);
            armor.add(el);
            if (el instanceof JsonPrimitive) tagged += hasComponents(s.homeArmor[i]) ? 1 : 0;
        }
        JsonElement offEl = encodeStack(s.homeOff[0], enhanced, ops);
        if (offEl instanceof JsonPrimitive) tagged += hasComponents(s.homeOff[0]) ? 1 : 0;

        o.add("homeMain", main);
        o.add("homeArmor", armor);
        o.add("homeOff", offEl);
        if (tagged > 0 && COMPONENT_WARNED.add(s.uuid)) {
            L10n.warn(s.playerName + " 的背包里有 " + tagged + " 件带组件（附魔等）的物品，纯文本编码只记了种类和数量；"
                    + " 把配置 storage.item_encoding 改成 enhanced 可完整保留。",
                    s.playerName + " has " + tagged + " component-carrying item(s) stored as plain id|count; "
                    + "set storage.item_encoding to enhanced to keep them fully.");
        }
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RegistryOps<JsonElement> asJsonOps(MinecraftServer server) {
        return (RegistryOps<JsonElement>) (RegistryOps) server.getRegistryManager().getOps(JsonOps.INSTANCE);
    }

    private static boolean hasComponents(ItemStack stack) {
        return stack != null && !stack.isEmpty() && !stack.getComponents().isEmpty();
    }

    private static JsonElement encodeStack(ItemStack stack, boolean enhanced, RegistryOps<JsonElement> ops) {
        if (stack == null || stack.isEmpty()) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (enhanced && ops != null) {
            try {
                JsonElement el = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
                if (el != null && el.isJsonObject()) return el;
            } catch (Exception ignored) {
                // 编码失败退回纯文本，绝不因一个槽位丢整条记录
            }
        }
        return new JsonPrimitive(Registries.ITEM.getId(stack.getItem()).toString() + "|" + stack.getCount());
    }

    private static BanishmentManager.State fromJson(MinecraftServer server, String text, UUID fallbackId) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        UUID id = fallbackId;
        if (o.has("uuid") && o.get("uuid").isJsonPrimitive()) {
            UUID parsed = parseUuid(o.get("uuid").getAsString());
            if (parsed != null) id = parsed;
        }
        String name = o.has("player") ? o.get("player").getAsString() : "?";
        BanishmentManager.State s = new BanishmentManager.State(id, name);

        s.originDim = readDimension(o, "originDimension", "originDim", World.OVERWORLD, name);
        s.ox = readDouble(o, "x", 0);
        s.oy = readDouble(o, "y", 0);
        s.oz = readDouble(o, "z", 0);
        s.yaw = readFloat(o, "yaw", 0);
        s.pitch = readFloat(o, "pitch", 0);
        GameMode gm = GameMode.getOrNull(readInt(o, "gameType", 0));
        s.originGameType = gm == null || gm == GameMode.DEFAULT ? GameMode.SURVIVAL : gm;

        s.originSpawnDim = readDimensionOrNull(o, "spawnDimension", "spawnDim");
        if (o.has("spawnPos") && o.get("spawnPos").isJsonArray()) {
            JsonArray pos = o.getAsJsonArray("spawnPos");
            if (pos.size() >= 3) {
                s.originSpawnPos = new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            }
        }
        s.originSpawnAngle = readFloat(o, "spawnAngle", 0);
        s.originSpawnForced = readBool(o, "spawnForced", false);
        s.pendingRelease = readBool(o, "pendingRelease", false);
        s.savedAt = readLong(o, "savedAt", 0L);
        s.banishtimeRemainingMs = Math.max(0L, readLong(o, "banishtimeRemainingMs", 0L));

        RegistryOps<JsonElement> ops = server != null ? asJsonOps(server) : null;
        readSlots(o, "homeMain", s.homeMain, name, ops);
        readSlots(o, "homeArmor", s.homeArmor, name, ops);
        if (o.has("homeOff")) {
            s.homeOff[0] = decodeStack(o.get("homeOff"), name + " 的副手", "offhand", ops);
        }
        return s;
    }

    private static void readSlots(JsonObject o, String key, ItemStack[] into, String owner, RegistryOps<JsonElement> ops) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return;
        JsonArray arr = o.getAsJsonArray(key);
        for (int i = 0; i < into.length && i < arr.size(); i++) {
            into[i] = decodeStack(arr.get(i), owner, key + " 第 " + i + " 格", ops);
        }
    }

    /** 槽位可以是 null、"id|count"、"id|meta|count"（r19 旧档）或组件对象。 */
    private static ItemStack decodeStack(JsonElement el, String owner, String slotDesc, RegistryOps<JsonElement> ops) {
        if (el == null || el.isJsonNull()) {
            return ItemStack.EMPTY;
        }
        if (el.isJsonObject() && ops != null) {
            try {
                ItemStack stack = ItemStack.CODEC.decode(ops, el).result()
                        .map(p -> p.getFirst()).orElse(ItemStack.EMPTY);
                return stack;
            } catch (Exception e) {
                L10n.warn(owner + " 的 " + slotDesc + " 组件解析失败，已跳过 / failed to decode component stack: " + e);
                return ItemStack.EMPTY;
            }
        }
        if (!el.isJsonPrimitive()) {
            return ItemStack.EMPTY;
        }
        String[] p = el.getAsString().split("\\|");
        if (p.length < 2 || p[0].isEmpty()) {
            return ItemStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(p[0]);
        if (id == null || !Registries.ITEM.containsId(id)) {
            L10n.warn(owner + " 的 " + slotDesc + " 里的物品 " + p[0]
                    + " 已不存在（对应模组可能被移除），已跳过。",
                    owner + "'s slot " + slotDesc + " references missing item " + p[0] + "; skipped.");
            return ItemStack.EMPTY;
        }
        try {
            Item item = Registries.ITEM.get(id);
            // 旧档三段式 "id|meta|count"：meta 在新版没有对应概念，只取数量
            int count = Integer.parseInt(p.length >= 3 ? p[2] : p[1]);
            return new ItemStack(item, Math.max(1, count));
        } catch (NumberFormatException e) {
            L10n.warn(owner + " 的 " + slotDesc + " 槽位数值异常，已跳过 / bad slot value: " + el);
            return ItemStack.EMPTY;
        }
    }

    // ---- 维度引用：新版字符串优先，旧版整数 ID 兜底 ----
    private static RegistryKey<World> readDimension(JsonObject o, String strKey, String legacyKey, RegistryKey<World> def, String owner) {
        RegistryKey<World> k = readDimensionOrNull(o, strKey, legacyKey);
        return k == null ? def : k;
    }

    private static RegistryKey<World> readDimensionOrNull(JsonObject o, String strKey, String legacyKey) {
        if (o.has(strKey) && o.get(strKey).isJsonPrimitive()) {
            String raw = o.get(strKey).getAsString();
            if (!raw.isEmpty()) {
                Identifier id = Identifier.tryParse(raw);
                if (id != null) {
                    RegistryKey<World> k = RegistryKey.of(RegistryKeys.WORLD, id);
                    if (BanishmentDimensions.isBanishment(k)) {
                        // 记录里出现放逐维度本身（异常数据）：按主世界处理
                        return World.OVERWORLD;
                    }
                    return k;
                }
            }
        }
        if (o.has(legacyKey) && o.get(legacyKey).isJsonPrimitive()) {
            int legacy = o.get(legacyKey).getAsInt();
            switch (legacy) {
                case -1: return World.NETHER;
                case 0: return World.OVERWORLD;
                case 1: return World.END;
                default:
                    L10n.warn("旧记录里的维度 ID " + legacy + " 无法映射，已按主世界处理 / unknown legacy dim id");
                    return World.OVERWORLD;
            }
        }
        return null;
    }

    private static double readDouble(JsonObject o, String key, double def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
    }

    private static long readLong(JsonObject o, String key, long def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean readBool(JsonObject o, String key, boolean def) {
        try {
            if (!o.has(key) || !o.get(key).isJsonPrimitive()) return def;
            JsonElement v = o.get(key);
            if (v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
            String s = v.getAsString().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("0")) return false;
        } catch (Exception ignored) {
            // 落到默认值
        }
        return def;
    }

    private static float readFloat(JsonObject o, String key, float def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsFloat() : def;
    }

    private static int readInt(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
    }
}
