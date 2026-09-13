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
 * /escape   谁都可以使用的自救指令（目标只能是使用者自己）。
 *
 * 处理「通过除放逐以外的方式进入放逐维度 114、却出不去」的情况，让玩家自己就能离开：
 * - 仅当使用者【不在放逐名单】且【当前位于维度 114】时生效，把它送回进入 114 之前的位置；
 * - 只要有一项不符（被放逐、或根本不在 114），就只会提示一句，不做任何传送；
 * - 被放逐者请找 OP 用 /unbanish 解除，本指令对放逐者无效。
 * 权限：无（所有玩家可用，OP 等级 0 即可）。作者：VIASR。
 */
public class CommandEscape extends CommandBase {

    @Override
    public String getName() {
        return "escape";
    }

    /** 0 = 所有玩家都能用（OP 等级 0 的门槛）。 */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return L10n.usage("usage.escape");
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        // 本指令无参数，无需补全
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) {
            throw new WrongUsageException(getUsage(sender), new Object[0]);
        }
        // 只能对使用者自己生效：必须是游戏内的玩家。
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(L10n.bi("msg.err.escape_not_player"));
            return;
        }
        EntityPlayerMP mp = (EntityPlayerMP) sender;
        // 条件一：使用者必须不在放逐名单里。
        if (BanishmentManager.isBanished(mp)) {
            sender.sendMessage(L10n.bi("msg.err.escape_banished"));
            return;
        }
        // 条件二：使用者必须身处放逐维度 114。
        if (mp.dimension != BanishmentManager.DIM_ID) {
            sender.sendMessage(L10n.bi("msg.err.escape_not_in_dim"));
            return;
        }
        // 两项都符合：送回进入 114 之前的位置（无原位记录则退主世界出生点兜底）。
        BanishmentManager.returnNonBanishedToOrigin(mp);
    }
}
