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
 * /return &lt;玩家&gt;  强制把指定在线玩家送回主世界出生点（OP 专用逃生通道）。
 *
 * 用于处理「通过除放逐以外的方式进入放逐维度 114、却出不去」的情况：
 * - 只能对【未被放逐】的玩家使用；对已被放逐者 /return 会拒绝（请改用 /unbanish）。
 * - 对方只是越权闯入：直接送回主世界，身上物品随行不没收。
 * 权限：OP 等级 2 及以上（命令方块与控制台亦可用）。作者：VIASR。
 */
public class CommandReturn extends CommandBase {

    @Override
    public String getName() {
        return "return";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return L10n.usage("usage.return");
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 0;
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
        EntityPlayerMP target = server.getPlayerList().getPlayerByUsername(args[0]);
        if (target == null) {
            sender.sendMessage(L10n.bi("msg.err.not_online", args[0]));
            return;
        }
        if (BanishmentManager.isBanished(target)) {
            // 按本期要求：/return 不能对被放逐的人使用，只能对不在放逐名单里的人使用。
            // 要解除被放逐者的放逐，请用 /unbanish <玩家>。
            sender.sendMessage(L10n.bi("msg.err.return_banished", target.getName()));
            return;
        }
        BanishmentManager.forceReturn(target, sender.getName());
        sender.sendMessage(L10n.bi("msg.ok.returned", target.getName()));
    }
}
