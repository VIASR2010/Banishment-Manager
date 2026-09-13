package cn.blockforge.generated.banishmentdimension;

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
 * /banish &lt;玩家&gt;   将在线玩家放逐到放逐维度 114
 * /banish list      查看当前被放逐名单
 * 权限：OP 等级 2 及以上（命令方块与控制台亦可用）。作者：VIASR。
 */
public class CommandBanish extends CommandBase {

    @Override
    public String getName() {
        return "banish";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return L10n.usage("usage.banish");
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
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, server.getPlayerList().getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getUsage(sender), new Object[0]);
        }

        if (args[0].equalsIgnoreCase("list")) {
            java.util.List<BanishmentManager.State> states = BanishmentManager.banishedStates();
            if (states.isEmpty()) {
                sender.sendMessage(L10n.bi("msg.list.empty"));
            } else {
                // 名单逐段取中/英两份文案，最后按配置的语言折叠成一行或两行
                String[] header = L10n.pair("msg.list.header", String.valueOf(states.size()));
                String[] sep = L10n.pair("msg.list.sep");
                String[] tagPending = L10n.pair("msg.list.tag.pending");
                String[] tagOffline = L10n.pair("msg.list.tag.offline");
                StringBuilder zh = new StringBuilder(header[0]);
                StringBuilder en = new StringBuilder(header[1]);
                for (int i = 0; i < states.size(); i++) {
                    BanishmentManager.State s = states.get(i);
                    if (i > 0) {
                        zh.append(sep[0]);
                        en.append(sep[1]);
                    }
                    boolean online = server.getPlayerList().getPlayerByUUID(s.uuid) != null;
                    zh.append(" §a").append(s.playerName);
                    en.append(" §a").append(s.playerName);
                    if (s.pendingRelease) {
                        zh.append(tagPending[0]);
                        en.append(tagPending[1]);
                    } else if (!online) {
                        zh.append(tagOffline[0]);
                        en.append(tagOffline[1]);
                    }
                    if (s.banishtimeRemainingMs > 0L) {
                        String[] timed = L10n.pair("msg.list.tag.timed",
                                String.valueOf(BanishmentManager.remainingMinutes(s)));
                        zh.append(timed[0]);
                        en.append(timed[1]);
                    }
                }
                sender.sendMessage(L10n.lines(zh.toString(), en.toString()));
                sender.sendMessage(L10n.bi("msg.list.note", BanishmentStorage.FOLDER));
            }
            return;
        }

        EntityPlayerMP target = getPlayer(server, sender, args[0]);
        if (BanishmentManager.isBanished(target)) {
            sender.sendMessage(L10n.bi("msg.err.already_banished", target.getName()));
            return;
        }
        String by = sender.getName();
        BanishmentManager.banish(target, by);
        sender.sendMessage(L10n.bi("msg.ok.banished", target.getName(), String.valueOf(BanishmentManager.DIM_ID)));
    }
}
