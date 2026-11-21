package dev.nulltotem.clienttest;

import dev.nulltotem.ModItems;
import dev.nulltotem.SoldierHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dev-only: makes a flat world, puts the Totem of Null in the off hand, summons soldiers with a real sneak +
 * right-click, screenshots them stepping out of the portal and up close, pops the totem, screenshots again, and quits.
 * Lines start with [NULLTEST]; screenshots land in run/clienttest/screenshots.
 */
public final class Harness implements ClientModInitializer {
    private static final String LEVEL = "nulltest";
    private static final String RENAME = "Totem of Null";

    private static int step;
    private static int wait;
    private static int ground;

    @Override
    public void onInitializeClient() {
        if (System.getProperty("nulltotem.clienttest") != null) {
            ClientTickEvents.END_CLIENT_TICK.register(Harness::tick);
            log("armed");
        }
    }

    private static void next() {
        step++;
        wait = 0;
    }

    private static void tick(Minecraft mc) {
        mc.options.pauseOnLostFocus = false;
        mc.options.tutorialStep = TutorialSteps.NONE;
        if (mc.screen instanceof PauseScreen) {
            mc.setScreen(null);
        }
        if (mc.player != null && step >= 3) {
            mc.player.setYRot(0); // face south, where the portal goes; the real mouse over the window would turn the view
            mc.player.setXRot(12);
        }
        wait++;
        switch (step) {
            case 0 -> {
                if (mc.screen instanceof TitleScreen && wait > 20) {
                    createWorld(mc);
                    next();
                }
            }
            case 1 -> {
                if (mc.player != null && mc.getSingleplayerServer() != null && mc.screen == null && wait > 60) {
                    setUp(mc);
                    next();
                }
            }
            case 2 -> { // the real anvil path: type the name like AnvilScreen does, then shift-click the result out
                if (wait == 10) {
                    ((AnvilMenu) mc.player.containerMenu).setItemName(RENAME);
                    mc.getConnection().send(new ServerboundRenameItemPacket(RENAME));
                }
                if (wait == 25) {
                    AnvilMenu anvil = (AnvilMenu) mc.player.containerMenu;
                    log("anvil result " + anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem().getItem() + ", cost " + anvil.getCost()
                            + ", player levels " + mc.player.experienceLevel);
                    shot(mc, "0_anvil");
                }
                if (wait == 35) {
                    mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, AnvilMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, mc.player);
                }
                if (wait == 45) {
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        player.closeContainer();
                        int slot = -1;
                        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                            if (player.getInventory().getItem(i).is(ModItems.TOTEM_OF_NULL)) {
                                slot = i;
                            }
                        }
                        ItemStack made = slot < 0 ? ItemStack.EMPTY : player.getInventory().removeItemNoUpdate(slot);
                        log("after taking: levels " + player.experienceLevel + ", totem in slot " + slot + ", works "
                                + ModItems.isCurrent(made, server) + ", vanilla totems left " + player.getInventory().countItem(Items.TOTEM_OF_UNDYING));
                        player.setItemSlot(EquipmentSlot.OFFHAND, made);
                    });
                    next();
                }
            }
            case 3 -> { // the real input path: sneak, then right-click with an empty main hand
                mc.options.keyShift.setDown(true);
                mc.options.keyUse.setDown(wait >= 10 && wait < 13);
                if (wait > 20) {
                    mc.options.keyShift.setDown(false);
                    log("summon pressed");
                    next();
                }
            }
            case 4 -> {
                if (wait == 30) {
                    shot(mc, "1_stepping_out");
                }
                if (wait % 10 == 0 && wait <= 100) {
                    int at = wait;
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.execute(() -> log("+" + at + " ticks: portal " + server.overworld().getBlockState(new BlockPos(0, ground + 1, 6)).getBlock()
                            + ", players online " + server.getPlayerList().getPlayerCount() + ", client off hand " + mc.player.getOffhandItem().getItem()));
                }
                if (wait == 160) {
                    report(mc);
                    closeUp(mc);
                    next();
                }
            }
            case 5 -> {
                if (wait == 40) {
                    shot(mc, "2_close_up");
                    logSkins(mc);
                }
                if (wait == 60) {
                    pop(mc);
                    next();
                }
            }
            case 6 -> {
                if (wait == 40) {
                    shot(mc, "3_after_pop");
                    report(mc);
                }
                if (wait == 60) {
                    log("done");
                    mc.stop();
                }
            }
            default -> {
            }
        }
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
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            List<ServerPlayer> soldiers = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.getTeam() != null && p.getTeam().getName().equals(SoldierHandler.TEAM)).toList();
            log("soldiers: " + soldiers.size() + " " + soldiers.stream().map(ServerPlayer::getScoreboardName).toList());
            soldiers.stream().findFirst().ifPresent(s -> log("shield: base " + s.getOffhandItem().get(DataComponents.BASE_COLOR)
                    + ", layers " + s.getOffhandItem().get(DataComponents.BANNER_PATTERNS).layers().stream()
                    .map(l -> l.color() + " " + l.pattern().unwrapKey().orElseThrow().identifier().getPath()).toList()));
            BlockPos inside = new BlockPos(0, ground + 1, 6);
            log("portal at " + inside + ": " + level.getBlockState(inside) + ", frame below: " + level.getBlockState(inside.below()));
        });
        var offhand = mc.player.getOffhandItem();
        log("client off hand: " + offhand.getItem() + " glint=" + offhand.hasFoil() + " lore=" + offhand.get(DataComponents.LORE));
    }

    /** Lines three soldiers up 2.5 blocks in front of the camera; they then face their master. */
    private static void closeUp(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            List<ServerPlayer> soldiers = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.getTeam() != null && p.getTeam().getName().equals(SoldierHandler.TEAM)).limit(3).toList();
            double[] xs = {-0.8, 0.5, 1.8};
            for (int i = 0; i < soldiers.size(); i++) {
                soldiers.get(i).teleportTo(level, xs[i], ground, 3.0, Set.of(), 180, 0, false);
            }
            // push the rest out of the shot
            server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.getTeam() != null && p.getTeam().getName().equals(SoldierHandler.TEAM)).skip(3)
                    .forEach(p -> p.teleportTo(level, p.getX() < 0.5 ? -3 : 4, ground, -1, Set.of(), 0, 0, false));
        });
    }

    private static void logSkins(Minecraft mc) {
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            log("skin of " + info.getProfile().name() + ": " + info.getSkin().body().texturePath());
        }
    }

    private static void pop(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.getTeam() == null).findFirst().orElseThrow();
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
