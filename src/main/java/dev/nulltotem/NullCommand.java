package dev.nulltotem;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class NullCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // orders only move your own soldiers, so anyone may give them; everything else is for operators
        var op = (java.util.function.Predicate<CommandSourceStack>) s -> s.hasPermission(2);
        dispatcher.register(literal("null")
                .then(literal("orders").executes(c -> Orders.openMenu(c.getSource().getPlayerOrException())))
                .then(literal("order")
                        .then(literal("follow").executes(c -> Orders.follow(c.getSource().getPlayerOrException())))
                        .then(literal("attack").executes(c -> Orders.attackLooked(c.getSource().getPlayerOrException())))
                        .then(literal("mine").executes(c -> Orders.mineLooked(c.getSource().getPlayerOrException())))
                        .then(literal("bridge").executes(c -> Orders.bridge(c.getSource().getPlayerOrException())))
                        .then(literal("summon").executes(c -> Orders.summon(c.getSource().getPlayerOrException())))
                        .then(literal("dismiss").executes(c -> Orders.dismiss(c.getSource().getPlayerOrException()))))
                .then(literal("give").requires(op).executes(NullCommand::give))
                .then(literal("tag").requires(op).then(argument("players", EntityArgument.players()).executes(c -> tag(c, true))))
                .then(literal("untag").requires(op).then(argument("players", EntityArgument.players()).executes(c -> tag(c, false))))
                .then(literal("soldiers").requires(op)
                        .then(literal("spawn")
                                .executes(c -> spawn(c, NullState.HARD_CAP))
                                .then(argument("amount", IntegerArgumentType.integer(1, NullState.HARD_CAP))
                                        .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "amount")))))
                        .then(literal("clear").executes(NullCommand::clear)))
                .then(literal("config").requires(op)
                        .executes(NullCommand::openConfig)
                        .then(literal("maxSoldiers").then(argument("value", IntegerArgumentType.integer(1, NullState.HARD_CAP))
                                .executes(c -> setMax(c, IntegerArgumentType.getInteger(c, "value")))))));
    }

    private static int give(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack totem = Totems.give(player); // an op's override: this one replaces whoever had it
        if (!player.getInventory().add(totem)) {
            player.drop(totem, false);
        }
        c.getSource().sendSuccess(() -> Component.literal("Gave the Totem of Null. Any older one is now void: there can only be one. Hold it to be saved from death, "
                + "tag victims with /null tag <player>, and sneak + right-click it in your off hand to summon soldiers."), false);
        return 1;
    }

    private static int tag(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        NullState state = NullState.get(c.getSource().getServer());
        int changed = 0;
        for (ServerPlayer player : players) {
            if (add ? state.tagged.add(player.getUUID()) : state.tagged.remove(player.getUUID())) {
                changed++;
            }
        }
        state.setDirty();
        int total = state.tagged.size();
        int n = changed;
        c.getSource().sendSuccess(() -> Component.literal((add ? "Tagged " : "Untagged ") + n + " player(s). " + total + " on the Null hit list."), true);
        return changed;
    }

    private static int spawn(CommandContext<CommandSourceStack> c, int amount) throws CommandSyntaxException {
        ServerPlayer master = c.getSource().getPlayerOrException();
        int started = Soldiers.spawn(master, amount);
        int max = NullState.get(c.getSource().getServer()).maxSoldiers();
        if (started == 0) {
            c.getSource().sendFailure(Component.literal("No soldiers spawned: you already have the max of " + max
                    + ". Remove them with /null soldiers clear or change the max with /null config."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Spawning " + started + " Null Soldier(s) (max " + max
                + "). They follow you and attack what you hit or what hits you."), true);
        return started;
    }

    private static int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int removed = Soldiers.clear(c.getSource().getPlayerOrException());
        c.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " Null Soldier(s)."), true);
        return removed;
    }

    private static int setMax(CommandContext<CommandSourceStack> c, int value) {
        NullState.get(c.getSource().getServer()).setMaxSoldiers(value);
        c.getSource().sendSuccess(() -> Component.literal("Max Null Soldiers per player set to " + value + "."), true);
        return value;
    }

    /** The settings window (a client screen, see NullClient.ConfigScreen); its Save button runs /null config maxSoldiers. */
    private static int openConfig(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Payloads.sendTo(player, new Payloads.OpenConfig(NullState.get(c.getSource().getServer()).maxSoldiers()));
        return 1;
    }
}
