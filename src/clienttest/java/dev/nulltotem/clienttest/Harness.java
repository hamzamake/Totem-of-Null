package dev.nulltotem.clienttest;

import dev.nulltotem.ModItems;
import dev.nulltotem.SoldierHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Dev-only: plays through the mod with real input in a flat world and screenshots each part, then quits. The anvil
 * rename, the off-hand summon, the orders menu (sneak + right-click, main hand), sending the soldiers to a spot and
 * calling them back and sending them at a mob (sneak + left-click, off hand), the bridge (sneak + left-click, main
 * hand) and the pop. Lines start with [NULLTEST]; screenshots land in run/clienttest/screenshots.
 */
public final class Harness implements ClientModInitializer {
    private static final String LEVEL = "nulltest";
    private static final String RENAME = "Totem of Null";

    /** One scripted action, then how many ticks to wait before the next. */
    private record Step(Consumer<Minecraft> action, int waitAfter) {
    }

    private static final List<Step> steps = new ArrayList<>();
    private static int next;
    private static int wait;
    private static boolean started;
    private static int ground;
    private static float yaw;
    private static float pitch = 12;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("nulltotem.clienttest") != null) {
            script();
            ClientTickEvents.END_CLIENT_TICK.register(Harness::tick);
            log("armed");
        }
    }

    private static void step(int waitAfter, Consumer<Minecraft> action) {
        steps.add(new Step(action, waitAfter));
    }

    private static void script() {
        // the anvil: type the name like AnvilScreen does, then shift-click the result out
        step(10, mc -> {
            ((AnvilMenu) mc.player.containerMenu).setItemName(RENAME);
            mc.getConnection().send(new ServerboundRenameItemPacket(RENAME));
        });
        step(10, mc -> {
            AnvilMenu anvil = (AnvilMenu) mc.player.containerMenu;
            log("anvil result " + anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem().getItem() + ", cost " + anvil.getCost() + ", levels " + mc.player.experienceLevel);
            shot(mc, "0_anvil");
        });
        step(10, mc -> mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, AnvilMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, mc.player));
        step(10, mc -> onServer(mc, (server, player) -> {
            player.closeContainer();
            ItemStack made = takeTotem(player);
            log("after taking: levels " + player.experienceLevel + ", works " + ModItems.isCurrent(made, server));
            player.setItemSlot(EquipmentSlot.OFFHAND, made);
        }));

        // summon: sneak + right-click with the totem in the off hand
        step(10, mc -> mc.options.keyShift.setDown(true));
        step(3, mc -> mc.options.keyUse.setDown(true));
        step(10, mc -> {
            mc.options.keyUse.setDown(false);
            mc.options.keyShift.setDown(false);
        });
        step(130, mc -> shot(mc, "1_stepping_out"));
        step(40, mc -> {
            report(mc);
            closeUp(mc);
        });
        step(20, mc -> {
            shot(mc, "2_close_up");
            logSkins(mc);
        });

        // orders menu: sneak + right-click with the totem in the main hand
        step(10, mc -> onServer(mc, (server, player) -> player.setItemSlot(EquipmentSlot.MAINHAND, takeTotem(player))));
        step(5, mc -> mc.options.keyShift.setDown(true));
        step(3, mc -> mc.options.keyUse.setDown(true));
        step(20, mc -> {
            mc.options.keyUse.setDown(false);
            mc.options.keyShift.setDown(false);
        });
        step(5, mc -> {
            log("screen after sneak + right-click (main hand): " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
            shot(mc, "3_orders_menu");
        });
        step(10, mc -> mc.setScreen(null));

        // send them to a spot 10 blocks ahead, then call them back (sneak + left-click, off hand)
        step(10, mc -> {
            onServer(mc, (server, player) -> player.setItemSlot(EquipmentSlot.OFFHAND, takeTotem(player)));
            pitch = (float) Math.toDegrees(Math.atan2(1.62, 10));
        });
        step(5, mc -> mc.options.keyShift.setDown(true));
        step(100, mc -> {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            mc.options.keyShift.setDown(false);
        });
        step(5, mc -> {
            distances(mc, new Vec3(0.5, ground, 10.5), "post at 10 blocks");
            shot(mc, "4_post");
            mc.options.keyShift.setDown(true);
        });
        step(100, mc -> {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            mc.options.keyShift.setDown(false);
            pitch = 12;
        });
        step(5, mc -> distances(mc, mc.player.position(), "called back to the player"));

        // send them at a pig (sneak + left-click, off hand, looking at it): to the west, clear of the portal and anvil
        step(20, mc -> onServer(mc, (server, player) -> {
            player.teleportTo(server.overworld(), 0.5, ground, 0.5, Set.of(), 90, 0, false);
            var pig = EntityType.PIG.spawn(server.overworld(), new BlockPos(-9, ground, 0), EntitySpawnReason.COMMAND);
            pig.setNoAi(true);
        }));
        step(5, mc -> {
            yaw = 90;
            pitch = (float) Math.toDegrees(Math.atan2(1.62 - 0.45, 9.5));
            mc.options.keyShift.setDown(true);
        });
        step(60, mc -> {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            mc.options.keyShift.setDown(false);
        });
        step(5, mc -> onServer(mc, (server, player) -> log("pigs left after the attack order: "
                + server.overworld().getEntitiesOfClass(Animal.class, new AABB(player.blockPosition()).inflate(20)).size())));

        // bridge: stand on a pillar, totem in the main hand, look level, sneak + left-click
        step(20, mc -> {
            yaw = 0;
            pitch = 0;
            onServer(mc, (server, player) -> {
                ServerLevel level = server.overworld();
                level.setBlockAndUpdate(new BlockPos(0, ground + 4, -6), Blocks.STONE.defaultBlockState());
                player.teleportTo(level, 0.5, ground + 5, -5.5, Set.of(), 0, 0, false);
                player.setItemSlot(EquipmentSlot.MAINHAND, takeTotem(player));
            });
        });
        step(5, mc -> mc.options.keyShift.setDown(true));
        step(10, mc -> {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            mc.options.keyShift.setDown(false);
        });
        step(5, mc -> {
            onServer(mc, (server, player) -> {
                int count = 0;
                for (int z = -6; z < 64; z++) {
                    if (server.overworld().getBlockState(new BlockPos(0, ground + 4, z)).is(Blocks.NETHERRACK)) {
                        count++;
                    }
                }
                log("netherrack in the bridge line: " + count);
            });
            pitch = 25;
        });
        step(5, mc -> shot(mc, "5_bridge"));

        // pop
        step(40, mc -> {
            pitch = 12;
            pop(mc);
        });
        step(20, mc -> {
            shot(mc, "6_after_pop");
            report(mc);
        });
        step(0, mc -> {
            log("done");
            mc.stop();
        });
    }

    private static void tick(Minecraft mc) {
        mc.options.pauseOnLostFocus = false;
        mc.options.tutorialStep = TutorialSteps.NONE;
        if (mc.screen instanceof PauseScreen) {
            mc.setScreen(null);
        }
        if (!started) {
            if (mc.screen instanceof TitleScreen && ++wait > 20) {
                createWorld(mc);
                started = true;
                wait = -1;
            }
            return;
        }
        if (wait < 0) { // waiting to be in the world
            if (mc.player != null && mc.getSingleplayerServer() != null && mc.screen == null && --wait < -60) {
                setUp(mc);
                wait = 20;
            }
            return;
        }
        if (mc.player != null && next > 3) {
            mc.player.setYRot(yaw); // the real mouse over the window would otherwise turn the view
            mc.player.setXRot(pitch);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (next < steps.size()) {
            Step step = steps.get(next++);
            step.action().accept(mc);
            wait = step.waitAfter();
        }
    }

    private interface ServerAction {
        void run(IntegratedServer server, ServerPlayer player);
    }

    private static void onServer(Minecraft mc, ServerAction action) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> action.run(server, server.getPlayerList().getPlayers().stream()
                .filter(p -> SoldierHandler.masterOf(p) == null).findFirst().orElseThrow()));
    }

    /** Takes the Totem of Null out of wherever it is in the player's inventory. */
    private static ItemStack takeTotem(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.TOTEM_OF_NULL)) {
                return player.getInventory().removeItemNoUpdate(i);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void distances(Minecraft mc, Vec3 from, String what) {
        onServer(mc, (server, player) -> log(what + ": soldier distances " + SoldierHandler.soldiersOf(server, player.getUUID()).stream()
                .map(s -> String.format("%.1f", s.position().distanceTo(from))).toList()));
    }

    private static void createWorld(Minecraft mc) {
        try {
            Path save = mc.gameDirectory.toPath().resolve("saves").resolve(LEVEL);
            if (Files.exists(save)) {
                try (Stream<Path> walk = Files.walk(save)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException e) {
            log("couldn't delete the old save: " + e);
        }
        LevelSettings settings = new LevelSettings(LEVEL, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
        log("creating world");
        mc.createWorldOpenFlows().createFreshLevel(LEVEL, settings, WorldOptions.defaultWithRandomSeed(),
                access -> access.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private static void setUp(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            level.setDayTime(6000);
            ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            player.teleportTo(level, 0.5, ground, 0.5, Set.of(), 0, 12, false);
            player.setExperienceLevels(25);
            // an anvil with a plain Totem of Undying in it, to rename into the Totem of Null
            BlockPos anvil = new BlockPos(3, ground, 0);
            level.setBlockAndUpdate(anvil, Blocks.ANVIL.defaultBlockState());
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new AnvilMenu(id, inventory, ContainerLevelAccess.create(level, anvil)),
                    Component.literal("Anvil")));
            player.containerMenu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(Items.TOTEM_OF_UNDYING));
            log("set up; ground y " + ground);
        });
    }

    private static void report(Minecraft mc) {
        onServer(mc, (server, player) -> {
            ServerLevel level = server.overworld();
            List<ServerPlayer> soldiers = SoldierHandler.soldiersOf(server, player.getUUID());
            log("soldiers: " + soldiers.size() + " " + soldiers.stream().map(ServerPlayer::getScoreboardName).toList());
            soldiers.stream().findFirst().ifPresent(s -> log("armour enchantments: " + s.getItemBySlot(EquipmentSlot.CHEST).getEnchantments()));
            BlockPos inside = new BlockPos(0, ground + 1, 6);
            log("portal at " + inside + ": " + level.getBlockState(inside) + ", frame below: " + level.getBlockState(inside.below()));
        });
        ItemStack held = mc.player.getOffhandItem().isEmpty() ? mc.player.getMainHandItem() : mc.player.getOffhandItem();
        log("client totem: " + held.getItem() + " glint=" + held.hasFoil() + " lore lines=" + (held.get(DataComponents.LORE) == null ? 0 : held.get(DataComponents.LORE).lines().size()));
    }

    /** Lines three soldiers up 2.5 blocks in front of the camera; they then face their master. */
    private static void closeUp(Minecraft mc) {
        onServer(mc, (server, player) -> {
            ServerLevel level = server.overworld();
            List<ServerPlayer> soldiers = SoldierHandler.soldiersOf(server, player.getUUID());
            double[] xs = {-0.8, 0.5, 1.8};
            for (int i = 0; i < soldiers.size(); i++) {
                if (i < 3) {
                    soldiers.get(i).teleportTo(level, xs[i], ground, 3.0, Set.of(), 180, 0, false);
                } else { // the rest out of the shot
                    ServerPlayer s = soldiers.get(i);
                    s.teleportTo(level, s.getX() < 0.5 ? -3 : 4, ground, -1, Set.of(), 0, 0, false);
                }
            }
        });
    }

    private static void logSkins(Minecraft mc) {
        long black = mc.getConnection().getOnlinePlayers().stream()
                .filter(info -> info.getSkin().body().texturePath().getPath().contains("null_soldier")).count();
        log("players with the black skin: " + black + " of " + mc.getConnection().getOnlinePlayers().size());
    }

    private static void pop(Minecraft mc) {
        onServer(mc, (server, player) -> {
            player.hurtServer(player.level(), player.damageSources().generic(), 100);
            log("popped; health now " + player.getHealth());
        });
    }

    private static void shot(Minecraft mc, String name) {
        String file = "nulltest_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), 1,
                component -> log("shot " + new File(new File(mc.gameDirectory, "screenshots"), file)));
    }

    private static void log(String message) {
        System.out.println("[NULLTEST] " + message);
    }
}
