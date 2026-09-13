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
 * /unbanish &lt;玩家&gt;  解除放逐：传回原位、恢复原游戏模式与原背包（内存对象级还原，零 NBT）。
 * 玩家离线时：在其记录文件里挂"待解除"标记，下次登录自动执行完整解除。
 * 权限：OP 等级 2 及以上。作者：VIASR。
 */
public class CommandUnbanish extends CommandBase {

    @Override
    public String getName() {
        return "unbanish";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return L10n.usage("usage.unbanish");
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
            // 在线玩家 + 放逐记录里的离线玩家都能补全
            return getListOfStringsMatchingLastWord(args, BanishmentManager.banishedNames());
        }
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getUsage(sender), new Object[0]);
        }
        EntityPlayerMP target = server.getPlayerList().getPlayerByUsername(args[0]);
        if (target != null) {
            if (!BanishmentManager.isBanished(target)) {
                sender.sendMessage(L10n.bi("msg.err.not_banished", target.getName()));
                return;
            }
            BanishmentManager.release(target, sender.getName());
            sender.sendMessage(L10n.bi("msg.ok.released", target.getName()));
            return;
        }
        // 离线分支：凭 banishment/ 文件夹里的记录挂"待解除"标记
        BanishmentManager.State s = BanishmentManager.findByName(args[0]);
        if (s == null) {
            sender.sendMessage(L10n.bi("msg.err.offline_no_record", args[0]));
            return;
        }
        if (s.pendingRelease) {
            sender.sendMessage(L10n.bi("msg.info.pending_already", s.playerName));
        } else {
            BanishmentManager.markPendingRelease(server, s);
            sender.sendMessage(L10n.bi("msg.ok.pending_marked", s.playerName));
        }
    }
}
