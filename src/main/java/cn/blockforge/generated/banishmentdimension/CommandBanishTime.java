package cn.blockforge.generated.banishmentdimension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

/**
 * /banishtime &lt;玩家&gt; &lt;分钟&gt;      只对「已被放逐」的玩家重设剩余时长（按在线分钟计时）。
 * /banishtime add &lt;玩家&gt; &lt;分钟&gt;   给已放逐玩家的剩余时长追加分钟。
 * /banishtime set &lt;玩家&gt; &lt;分钟&gt;   把已放逐玩家的剩余时长重设为指定分钟。
 * 三种形式都只作用于「已被 /banish 放逐」的玩家，对未被放逐的玩家一律拒绝。
 * 时间单位只能是整数分钟，不允许小数。权限：OP 等级 2 及以上。作者：VIASR。
 */
public class CommandBanishTime extends CommandBase {

    @Override
    public String getName() {
        return "banishtime";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return L10n.usage("usage.banishtime");
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        String[] names = playerNames(server);
        if (args.length == 1) {
            List<String> out = new ArrayList<String>();
            Collections.addAll(out, "add", "set");
            Collections.addAll(out, names);
            return getListOfStringsMatchingLastWord(args, out.toArray(new String[0]));
        }
        if (args.length == 2) {
            // add/set 形式下这里是玩家名；基础形式下这里应是分钟数，补全玩家名也无害
            return getListOfStringsMatchingLastWord(args, names);
        }
        return Collections.emptyList();
    }

    /** 在线玩家 + 放逐记录里的离线玩家名，供 /banishtime 补全。 */
    private static String[] playerNames(MinecraftServer server) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, server.getPlayerList().getOnlinePlayerNames());
        for (BanishmentManager.State s : BanishmentManager.banishedStates()) {
            if (!list.contains(s.playerName)) {
                list.add(s.playerName);
            }
        }
        return list.toArray(new String[0]);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(getUsage(sender), new Object[0]);
        }
        String mode = args[0].toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("add") || mode.equals("set")) {
            if (args.length < 3) {
                throw new WrongUsageException(getUsage(sender), new Object[0]);
            }
            Integer minutes = parseMinutes(args[2], sender);
            if (minutes == null) return;
            BanishmentManager.State s = findState(server, args[1]);
            if (s == null) {
                sender.sendMessage(L10n.bi("msg.err.not_banished", args[1]));
                return;
            }
            if (mode.equals("add")) {
                BanishmentManager.addBanishTime(server, s, minutes);
                sender.sendMessage(L10n.bi("msg.ok.banishtime_add", s.playerName, String.valueOf(minutes),
                        String.valueOf(BanishmentManager.remainingMinutes(s))));
            } else {
                BanishmentManager.setBanishTime(server, s, minutes);
                sender.sendMessage(L10n.bi("msg.ok.banishtime_set", s.playerName, String.valueOf(minutes),
                        String.valueOf(BanishmentManager.remainingMinutes(s))));
            }
            return;
        }

        // 基础形式：/banishtime <玩家名> <分钟> —— 只对「已被放逐」的玩家重设剩余时长
        Integer minutes = parseMinutes(args[1], sender);
        if (minutes == null) return;
        BanishmentManager.State s = findState(server, args[0]);
        if (s == null) {
            sender.sendMessage(L10n.bi("msg.err.not_banished", args[0]));
            return;
        }
        BanishmentManager.setBanishTime(server, s, minutes);
        sender.sendMessage(L10n.bi("msg.ok.banishtime_set", s.playerName, String.valueOf(minutes),
                String.valueOf(BanishmentManager.remainingMinutes(s))));
    }

    /** 解析分钟：只认正整数整数，零、小数、非数字都拒绝（失败已提示，返回 null）。 */
    private Integer parseMinutes(String raw, ICommandSender sender) {
        if (raw == null || raw.isEmpty()) {
            sender.sendMessage(L10n.bi("msg.err.banishtime_bad_minutes", String.valueOf(raw)));
            return null;
        }
        if (raw.indexOf('.') >= 0) {
            sender.sendMessage(L10n.bi("msg.err.banishtime_bad_minutes", raw));
            return null;
        }
        int m;
        try {
            m = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(L10n.bi("msg.err.banishtime_bad_minutes", raw));
            return null;
        }
        if (m <= 0) {
            sender.sendMessage(L10n.bi("msg.err.banishtime_bad_minutes", raw));
            return null;
        }
        return Integer.valueOf(m);
    }

    /** 取放逐状态：在线玩家取实时状态，离线玩家按记录里的名字找。 */
    private BanishmentManager.State findState(MinecraftServer server, String name) {
        EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(name);
        if (online != null) {
            return BanishmentManager.get(online);
        }
        return BanishmentManager.findByName(name);
    }
}
