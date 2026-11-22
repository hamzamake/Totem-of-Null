package dev.nulltotem.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Totem of Null for Paper: the plugin's entry point, its parts, and the /null commands. */
public final class NullTotemPlugin extends JavaPlugin {
    private NullState state;
    private Totems totems;
    private Soldiers soldiers;
    private Mining mining;
    private Bridges bridges;
    private Orders orders;

    NullState state() {
        return state;
    }

    Totems totems() {
        return totems;
    }

    Soldiers soldiers() {
        return soldiers;
    }

    Mining mining() {
        return mining;
    }

    Bridges bridges() {
        return bridges;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        state = new NullState(new File(getDataFolder(), "data.yml"), getLogger());
        mining = new Mining(this);
        bridges = new Bridges(this);
        soldiers = new Soldiers(this);
        totems = new Totems(this);
        orders = new Orders(this);
        getServer().getPluginManager().registerEvents(totems, this);
        getServer().getPluginManager().registerEvents(soldiers, this);
        getServer().getPluginManager().registerEvents(orders, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command(), "Totem of Null: orders, the hit list, soldiers and settings"));
    }

    @Override
    public void onDisable() {
        if (soldiers != null) {
            soldiers.shutdown();
        }
        if (state != null) {
            state.save();
        }
    }

    // ---- /null ----

    private static final Predicate<CommandSourceStack> OP = source -> source.getSender().hasPermission("nulltotem.admin");

    private static Player player(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (context.getSource().getExecutor() instanceof Player player) {
            return player;
        }
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(() -> "Only a player can do that").create();
    }

    private LiteralArgumentBuilder<CommandSourceStack> order(String name, ToIntFunction<Player> run) {
        return Commands.literal(name).executes(c -> run.applyAsInt(player(c)));
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> command() {
        // orders only move your own soldiers, so anyone may give them; everything else is for operators
        return Commands.literal("null")
                .then(Commands.literal("orders").executes(c -> orders.openMenu(player(c))))
                .then(Commands.literal("order")
                        .then(order("follow", orders::follow))
                        .then(order("attack", orders::attackLooked))
                        .then(order("mine", orders::mineLooked))
                        .then(order("bridge", orders::bridge))
                        .then(order("summon", orders::summon))
                        .then(order("dismiss", orders::dismiss)))
                .then(Commands.literal("give").requires(OP).executes(c -> {
                    Player player = player(c);
                    ItemStack totem = totems.give(player);
                    player.getInventory().addItem(totem).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                    c.getSource().getSender().sendMessage(Component.text("Gave the Totem of Null. Any older one is now void: there can only be one. "
                            + "Sneak + right-click it in your off hand to summon soldiers.", NamedTextColor.DARK_PURPLE));
                    return 1;
                }))
                .then(Commands.literal("tag").requires(OP).then(Commands.argument("players", ArgumentTypes.players()).executes(c -> tag(c, true))))
                .then(Commands.literal("untag").requires(OP).then(Commands.argument("players", ArgumentTypes.players()).executes(c -> tag(c, false))))
                .then(Commands.literal("soldiers").requires(OP)
                        .then(Commands.literal("spawn")
                                .executes(c -> spawn(c, NullState.HARD_CAP))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, NullState.HARD_CAP))
                                        .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "amount")))))
                        .then(Commands.literal("clear").executes(c -> {
                            int removed = soldiers.clear(player(c));
                            c.getSource().getSender().sendMessage(Component.text("Removed " + removed + " Null Soldier(s)."));
                            return removed;
                        })))
                .then(Commands.literal("config").requires(OP)
                        .executes(c -> orders.openSettings(player(c)))
                        .then(Commands.literal("maxSoldiers").then(Commands.argument("value", IntegerArgumentType.integer(1, NullState.HARD_CAP))
                                .executes(c -> {
                                    state.setMaxSoldiers(IntegerArgumentType.getInteger(c, "value"));
                                    c.getSource().getSender().sendMessage(Component.text("Max Null Soldiers per player set to " + state.maxSoldiers() + "."));
                                    return state.maxSoldiers();
                                }))))
                .build();
    }

    private int tag(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        List<Player> players = c.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(c.getSource());
        int changed = 0;
        for (Player player : players) {
            if (add ? state.tagged.add(player.getUniqueId()) : state.tagged.remove(player.getUniqueId())) {
                changed++;
            }
        }
        state.save();
        c.getSource().getSender().sendMessage(Component.text((add ? "Tagged " : "Untagged ") + changed + " player(s). "
                + state.tagged.size() + " on the Null hit list."));
        return changed;
    }

    private int spawn(CommandContext<CommandSourceStack> c, int amount) throws CommandSyntaxException {
        int started = soldiers.spawn(player(c), amount);
        c.getSource().getSender().sendMessage(started == 0
                ? Component.text("No soldiers spawned: you already have the max of " + state.maxSoldiers() + ".", NamedTextColor.RED)
                : Component.text("Spawning " + started + " Null Soldier(s) (max " + state.maxSoldiers() + ")."));
        return started;
    }
}
