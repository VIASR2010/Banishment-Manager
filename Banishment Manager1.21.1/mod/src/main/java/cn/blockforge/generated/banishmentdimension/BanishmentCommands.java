package cn.blockforge.generated.banishmentdimension;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 管理命令与玩家自救命令（行为等价迁移自 r19 · 作者：VIASR）。
 *
 *   banish   <玩家名|list>  权限2级   放逐在线玩家；list 子命令查看名单
 *   unbanish <玩家名>       权限2级   解除放逐（离线也能解除，登录时自动执行）
 *   return   <玩家名>       权限2级   把误入放逐维度的未放逐玩家送回主世界出生点
 *   banishtime <add|set> <玩家名> <分钟>  权限2级  给已放逐玩家追加/重设定时放逐时长
 *   escape                   无权限   玩家自救：未被放逐却困在放逐维度时回到进入前原位
 *
 *   定时放逐只累计「在线时长」：下线冻结、登录续走，归零自动解除（见 BanishmentManager）。
 *
 * 参数、权限、反馈语义与 r19 一致；用法提示按配置语言输出。
 */
public final class BanishmentCommands {

    /** 补全：在线玩家 ∪ 放逐名单（与 r19 的 TAB 补全范围一致）。 */
    private static final SuggestionProvider<ServerCommandSource> SUGGEST_BANISHED_OR_ONLINE =
            (ctx, builder) -> {
                List<String> names = BanishmentManager.banishedNames();
                for (String online : ctx.getSource().getServer().getPlayerManager().getPlayerNames()) {
                    if (!names.contains(online)) names.add(online);
                }
                for (String n : names) builder.suggest(n);
                return builder.buildFuture();
            };

    private BanishmentCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // ---- banish ----
        dispatcher.register(CommandManager.literal("banish")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> usage(ctx, "usage.banish"))
                .then(CommandManager.literal("list")
                        .executes(BanishmentCommands::list))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(BanishmentCommands::banishCmd)));

        // ---- unbanish ----
        dispatcher.register(CommandManager.literal("unbanish")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> usage(ctx, "usage.unbanish"))
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .suggests(SUGGEST_BANISHED_OR_ONLINE)
                        .executes(BanishmentCommands::unbanishCmd)));

        // ---- return ----
        dispatcher.register(CommandManager.literal("return")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> usage(ctx, "usage.return"))
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String n : ctx.getSource().getServer().getPlayerManager().getPlayerNames()) {
                                builder.suggest(n);
                            }
                            return builder.buildFuture();
                        })
                        .executes(BanishmentCommands::returnCmd)));

        // ---- banishtime add|set <player> <minutes>（定时放逐）----
        dispatcher.register(CommandManager.literal("banishtime")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> usage(ctx, "usage.banishtime"))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_BANISHED_OR_ONLINE)
                                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> banishtimeCmd(ctx, true)))))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_BANISHED_OR_ONLINE)
                                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> banishtimeCmd(ctx, false))))));

        // ---- escape（玩家自救，无权限要求）----
        dispatcher.register(CommandManager.literal("escape")
                .executes(BanishmentCommands::escapeCmd));
    }

    private static int usage(CommandContext<ServerCommandSource> ctx, String key) {
        ctx.getSource().sendFeedback(() -> L10n.render(L10n.usage(key)), false);
        return 0;
    }

    // ------------------------------------------------------------------
    // banish <player>
    // ------------------------------------------------------------------
    private static int banishCmd(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        if (BanishmentManager.isBanished(target)) {
            src.sendMessage(L10n.bi("msg.err.already_banished", target.getName().getString()));
            return 0;
        }
        BanishmentManager.banish(target, src.getName());
        final String targetName = target.getGameProfile().getName();
        final String dim = BanishmentDimensions.display();
        src.sendFeedback(() -> L10n.bi("msg.ok.banished", targetName, dim), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // banish list（与 r19 同款：表头计数 + 每人一行带 在线/离线/待上线解除 标签）
    // ------------------------------------------------------------------
    private static int list(CommandContext<ServerCommandSource> ctx) {
        List<BanishmentManager.State> states = BanishmentManager.banishedStates();
        if (states.isEmpty()) {
            ctx.getSource().sendFeedback(() -> L10n.bi("msg.list.empty"), false);
            return 0;
        }
        String[] header = L10n.pair("msg.list.header", String.valueOf(states.size()));
        String[] sep = L10n.pair("msg.list.sep");
        StringBuilder zhSb = new StringBuilder(header[0]);
        StringBuilder enSb = new StringBuilder(header[1]);
        for (int i = 0; i < states.size(); i++) {
            BanishmentManager.State s = states.get(i);
            String[] tag;
            long remain = BanishmentManager.remainingMinutes(s);
            if (s.pendingRelease) {
                tag = L10n.pair("msg.list.tag.pending");
            } else if (remain > 0L) {
                tag = L10n.pair("msg.list.tag.timed", String.valueOf(remain));
            } else if (ctx.getSource().getServer().getPlayerManager().getPlayer(s.uuid) == null) {
                tag = L10n.pair("msg.list.tag.offline");
            } else {
                tag = new String[] {"", ""};
            }
            if (i > 0) {
                zhSb.append(sep[0]);
                enSb.append(sep[1]);
            }
            zhSb.append("§e").append(s.playerName).append(' ').append(tag[0].trim());
            enSb.append("§e").append(s.playerName).append(' ').append(tag[1].trim());
        }
        final String zhBody = zhSb.toString().trim();
        final String enBody = enSb.toString().trim();
        ctx.getSource().sendFeedback(() -> L10n.lines(zhBody, enBody), false);
        ctx.getSource().sendFeedback(() -> L10n.bi("msg.list.note", BanishmentStorage.FOLDER), false);
        return states.size();
    }

    // ------------------------------------------------------------------
    // unbanish <player>（在线立即解除；离线挂待解除标记，登录时自动执行）
    // ------------------------------------------------------------------
    private static int unbanishCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target != null) {
            if (!BanishmentManager.isBanished(target)) {
                src.sendMessage(L10n.bi("msg.err.not_banished", target.getName().getString()));
                return 0;
            }
            final String shown = target.getGameProfile().getName();
            BanishmentManager.release(target, src.getName());
            src.sendFeedback(() -> L10n.bi("msg.ok.released", shown), false);
            return 1;
        }
        BanishmentManager.State s = BanishmentManager.findByName(name);
        if (s == null) {
            src.sendMessage(L10n.bi("msg.err.offline_no_record", name));
            return 0;
        }
        if (s.pendingRelease) {
            src.sendMessage(L10n.bi("msg.info.pending_already", s.playerName));
            return 1;
        }
        BanishmentManager.markPendingRelease(src.getServer(), s);
        final String offlineName = s.playerName;
        src.sendFeedback(() -> L10n.bi("msg.ok.pending_marked", offlineName), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // banishtime add|set <player> <minutes>（给已放逐玩家追加/重设定时时长）
    // ------------------------------------------------------------------
    private static int banishtimeCmd(CommandContext<ServerCommandSource> ctx, boolean add) {
        ServerCommandSource src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        ServerPlayerEntity online = src.getServer().getPlayerManager().getPlayer(name);
        BanishmentManager.State s = online != null
                ? BanishmentManager.get(online) : BanishmentManager.findByName(name);
        if (s == null) {
            if (online != null) {
                src.sendMessage(L10n.bi("msg.err.not_banished", online.getName().getString()));
            } else {
                src.sendMessage(L10n.bi("msg.err.offline_no_record", name));
            }
            return 0;
        }
        if (add) {
            BanishmentManager.addBanishTime(src.getServer(), s, minutes);
        } else {
            BanishmentManager.setBanishTime(src.getServer(), s, minutes);
        }
        final String shown = s.playerName;
        final long remain = BanishmentManager.remainingMinutes(s);
        src.sendFeedback(() -> L10n.bi(add ? "msg.ok.banishtime_add" : "msg.ok.banishtime_set",
                shown, String.valueOf(minutes), String.valueOf(remain)), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // return <player>（管理员把在线的未放逐违规进入者送回主世界出生点）
    // ------------------------------------------------------------------
    private static int returnCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            src.sendMessage(L10n.bi("msg.err.not_online", name));
            return 0;
        }
        if (BanishmentManager.isBanished(target)) {
            src.sendMessage(L10n.bi("msg.err.return_banished", target.getName().getString()));
            return 0;
        }
        BanishmentManager.forceReturn(target, src.getName());
        final String shown = target.getName().getString();
        src.sendFeedback(() -> L10n.bi("msg.ok.returned", shown), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // escape（玩家自救：未被放逐却困在放逐维度时回进入前原位）
    // ------------------------------------------------------------------
    private static int escapeCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity mp = src.getPlayer();
        if (mp == null) {
            src.sendMessage(L10n.bi("msg.err.escape_not_player"));
            return 0;
        }
        if (BanishmentManager.isBanished(mp)) {
            src.sendMessage(L10n.bi("msg.err.escape_banished"));
            return 0;
        }
        if (!mp.getWorld().getRegistryKey().equals(BanishmentDimensions.active())) {
            src.sendMessage(L10n.bi("msg.err.escape_not_in_dim", BanishmentDimensions.display()));
            return 0;
        }
        BanishmentManager.returnNonBanishedToOrigin(mp);
        return 1;
    }
}
