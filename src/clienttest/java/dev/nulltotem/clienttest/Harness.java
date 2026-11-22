package dev.nulltotem.clienttest;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * Dev-only: plays through the mod with a real client against the dev server (run/server, port 25568, NullTester is an
 * op). Summon, gear and skin, the orders menu and its buttons, post/recall, the attack order, defending against a
 * zombie, mining, the bridge, the settings slider, the anvil (refused while held, working after the pop), and the pop
 * erasing a tagged player. Lines start with [FORGETEST]; screenshots go to run/clienttest/screenshots.
 */
@Mod("nulltotemtest")
public final class Harness {
    private record Step(Consumer<Minecraft> action, int waitAfter) {
    }

    private static final List<Step> steps = new ArrayList<>();
    private static int next;
    private static int wait;
    private static int phase; // 0 title, 1 connecting, 2 running
    private static int idle;
    private static float yaw;
    private static float pitch = 12;
    private static String tagged = "";
    private static String eater = "";
    private static String faller = "";
    private static float lowest;
    public static int crits;
    private static final java.util.Map<Integer, Double> angles = new java.util.HashMap<>();
    private static Vec3 post = Vec3.ZERO; // where the last "heading to x y z" sent them

    public Harness() {
        if (System.getProperty("nulltotem.test") != null) {
            script();
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
                if (e.phase == TickEvent.Phase.END) {
                    tick(Minecraft.getInstance());
                }
            });
            MinecraftForge.EVENT_BUS.addListener((ClientChatReceivedEvent e) -> {
                String text = e.getMessage().getString();
                log("chat: " + text);
                var m = java.util.regex.Pattern.compile("heading to (-?[0-9]+) (-?[0-9]+) (-?[0-9]+)").matcher(text);
                if (m.find()) {
                    post = new Vec3(Integer.parseInt(m.group(1)) + 0.5, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) + 0.5);
                }
            });
            log("armed");
        }
    }

    private static void step(int waitAfter, Consumer<Minecraft> action) {
        steps.add(new Step(action, waitAfter));
    }

    private static void command(Minecraft mc, String command) {
        mc.getConnection().sendCommand(command);
    }

    private static void at(Minecraft mc, double x, double z, float yawDeg, float pitchDeg) {
        yaw = yawDeg;
        pitch = pitchDeg;
        command(mc, String.format("tp @s %.1f -60 %.1f %.1f %.1f", x, z, yawDeg, pitchDeg));
    }

    private static void sneakAttack(int waitAfter) {
        step(5, mc -> mc.options.keyShift.setDown(true));
        step(waitAfter, mc -> {
            KeyMapping.click(mc.options.keyAttack.getKey());
            mc.options.keyShift.setDown(false);
        });
    }

    private static void sneakUse(int waitAfter) {
        step(5, mc -> mc.options.keyShift.setDown(true));
        step(3, mc -> mc.options.keyUse.setDown(true));
        step(waitAfter, mc -> {
            mc.options.keyUse.setDown(false);
            mc.options.keyShift.setDown(false);
        });
    }

    private static void swapHands() {
        step(10, mc -> KeyMapping.click(mc.options.keySwapOffhand.getKey()));
    }

    private static void script() {
        step(20, mc -> {
            command(mc, "gamemode survival");
            command(mc, "time set day");
            command(mc, "gamerule doDaylightCycle false");
            command(mc, "clear @s");
            command(mc, "effect clear @s");
            command(mc, "null soldiers clear");
            command(mc, "null config maxSoldiers 20");
            command(mc, "kill @e[type=!player]");
            command(mc, "fill -16 -63 -30 16 -61 -14 dirt");
            command(mc, "fill -16 -60 -30 16 -50 -14 air");
        });
        step(20, mc -> {
            at(mc, 0.5, -24.5, 0, 12);
            command(mc, "null give");
        });
        swapHands(); // totem to the off hand

        // summon: sneak + right-click, off hand
        sneakUse(160);
        step(5, mc -> {
            log("soldiers seen after the summon: " + soldiers(mc).size());
            shot(mc, "n1_summon");
        });
        step(5, mc -> {
            yaw = 180;
            pitch = 5;
        });
        step(5, mc -> shot(mc, "n1b_soldiers_close"));

        // orders menu: sneak + right-click, main hand
        step(5, mc -> {
            yaw = 0;
            pitch = 12;
        });
        swapHands();
        sneakUse(20);
        step(5, mc -> {
            log("screen after sneak + right-click (main hand): " + screenName(mc));
            shot(mc, "n2_menu");
        });
        step(10, mc -> mc.setScreen(null));

        // hold a spot 10 blocks ahead, then call them back: sneak + left-click, off hand
        swapHands();
        step(5, mc -> pitch = (float) Math.toDegrees(Math.atan2(1.62, 10)));
        sneakAttack(40);
        step(40, mc -> log("post, 2 s: soldier distances " + distances(mc, post)));
        step(40, mc -> log("post, 4 s: soldier distances " + distances(mc, post)));
        step(5, mc -> {
            log("post at " + post + ": soldier distances " + distances(mc, post));
            shot(mc, "n3_post");
        });
        sneakAttack(120);
        step(5, mc -> log("called back: soldier distances " + distances(mc, mc.player.position())));

        // attack a pig: sneak + left-click, off hand, looking at it
        step(20, mc -> {
            command(mc, "summon pig 9.5 -60 -24.5 {NoAI:1b}");
            at(mc, 0.5, -24.5, -90, (float) Math.toDegrees(Math.atan2(1.62 - 0.45, 9)));
        });
        step(1, mc -> log("pigs before the attack order: " + count(mc, Animal.class)));
        sneakAttack(100);
        step(5, mc -> log("pigs left after the attack order: " + count(mc, Animal.class)));

        // defend: a zombie going after the master
        step(1, mc -> command(mc, "summon zombie 0.5 -60 -32.5"));
        step(200, mc -> log("zombies before defending: " + count(mc, Zombie.class)));
        step(5, mc -> log("zombies left after defending: " + count(mc, Zombie.class) + ", my health " + mc.player.getHealth()));

        // mine: the menu's "Mine blocks like this" button, looking at an iron ore pillar
        step(20, mc -> {
            command(mc, "fill 3 -60 -20 3 -58 -20 iron_ore");
            at(mc, 0.5, -24.5, (float) Math.toDegrees(Math.atan2(-(3.5 - 0.5), -20.5 + 24.5)), 0);
        });
        swapHands(); // totem to the main hand
        sneakUse(20);
        step(300, mc -> press(mc, "Mine blocks like this"));
        step(5, mc -> {
            int ore = 0;
            for (int y = -60; y <= -58; y++) {
                ore += mc.level.getBlockState(new BlockPos(3, y, -20)).is(Blocks.IRON_ORE) ? 1 : 0;
            }
            log("iron ore left " + ore + ", raw iron in my inventory " + mc.player.getInventory().countItem(Items.RAW_IRON));
            shot(mc, "n4_mined");
        });

        // bridge: a ditch to the west, sneak + left-click, main hand
        step(20, mc -> {
            command(mc, "fill -14 -63 -27 -5 -61 -22 air");
            at(mc, -1.5, -24.5, 90, 0);
        });
        sneakAttack(200);
        step(5, mc -> {
            pitch = 15;
            StringBuilder cells = new StringBuilder();
            for (int x = -5; x >= -14; x--) {
                cells.append(mc.level.getBlockState(new BlockPos(x, -61, -25)).is(Blocks.NETHERRACK) ? '#' : '.');
            }
            log("bridge across the 10-block ditch: " + cells);
        });
        step(5, mc -> shot(mc, "n5_bridge"));

        // combat: an ordered target that can take a beating (no AI, 1000 health): crits and circling
        swapHands(); // totem to the off hand
        step(20, mc -> {
            command(mc, "summon husk 8.5 -60 -24.5 {NoAI:1b,Health:1000f,Attributes:[{Name:\"generic.max_health\",Base:1000d}]}");
            at(mc, 0.5, -24.5, -90, (float) Math.toDegrees(Math.atan2(1.62 - 1.0, 8)));
        });
        step(5, mc -> crits = 0);
        sneakAttack(80);
        step(100, mc -> sampleAngles(mc, false));
        step(5, mc -> {
            sampleAngles(mc, true);
            log("crits in 9 s of fighting: " + crits);
            log("neutrality: lowest soldier health after 9 s of sword sweeps side by side "
                    + soldiers(mc).stream().mapToDouble(e -> ((RemotePlayer) e).getHealth()).min().orElse(-1));
            command(mc, "kill @e[type=husk]");
            command(mc, "null order follow");
        });

        // creeper: sent at a lit creeper, they back off instead of closing in
        step(60, mc -> command(mc, "summon creeper 8.5 -60 -24.5 {NoAI:1b,ignited:1b,Fuse:200s}"));
        step(5, mc -> log("creeper: soldier distances before " + distances(mc, new Vec3(8.5, -60, -24.5))));
        sneakAttack(60);
        step(5, mc -> {
            log("creeper: soldier distances 3 s after the attack order " + distances(mc, new Vec3(8.5, -60, -24.5))
                    + ", creeper still alive " + (count(mc, net.minecraft.world.entity.monster.Creeper.class) > 0));
            command(mc, "kill @e[type=creeper]");
            command(mc, "null order follow");
        });

        // pillar: the master on a 6-block column out of reach; the soldiers build up to them with netherrack
        step(20, mc -> {
            command(mc, "fill 0 -60 -40 0 -55 -40 stone");
            command(mc, "tp @s 0.5 -54 -39.5");
            pitch = 30;
        });
        step(400, mc -> log("pillar: master at y -54"));
        step(5, mc -> {
            long up = soldiers(mc).stream().filter(e -> e.getY() >= -55.1).count();
            log("pillar: soldiers up at the master's height " + up + " of " + soldiers(mc).size()
                    + ", netherrack under them " + soldiers(mc).stream().filter(e -> mc.level.getBlockState(e.blockPosition().below()).is(Blocks.NETHERRACK)).count());
            shot(mc, "n5b_pillar");
        });
        step(20, mc -> {
            command(mc, "fill -16 -60 -45 16 -40 -31 air");
            at(mc, 0.5, -24.5, 0, 12);
        });

        // another dimension: soldiers only teleport to follow their master there
        step(100, mc -> command(mc, "execute in minecraft:the_nether run tp @s 0.5 128 0.5"));
        step(5, mc -> log("in " + mc.level.dimension().location() + ": soldiers seen " + soldiers(mc).size()));
        step(100, mc -> command(mc, "execute in minecraft:overworld run tp @s 0.5 -60 -24.5"));
        step(5, mc -> log("back in " + mc.level.dimension().location() + ": soldiers seen " + soldiers(mc).size()));

        // golden apples: a soldier brought down to 2.5 hearts eats one
        step(40, mc -> {
            eater = name(soldiers(mc).get(0));
            command(mc, "damage " + eater + " 15 minecraft:out_of_world");
        });
        step(2, mc -> command(mc, "data get entity " + eater + " Health"));
        step(20, mc -> command(mc, "data get entity " + eater + " SelectedItem.id"));
        step(60, mc -> command(mc, "data get entity " + eater + " Health"));
        step(5, mc -> {
            command(mc, "data get entity " + eater + " Inventory[{Slot:4b}].Count");
            command(mc, "data get entity " + eater + " AbsorptionAmount");
        });

        // falls now count: a soldier dropped from 20 blocks up takes fall damage (cut down by Feather Falling)
        step(20, mc -> {
            faller = name(soldiers(mc).get(1));
            lowest = 20;
            command(mc, "execute as " + faller + " at @s run tp @s ~ ~20 ~");
        });
        for (int i = 0; i < 40; i++) {
            step(1, mc -> lowest = Math.min(lowest, health(mc, faller)));
        }
        step(5, mc -> log("fall: " + faller + " lowest health after a 20-block drop " + lowest));

        // a soldier that dies drops nothing
        step(20, mc -> command(mc, "kill " + name(soldiers(mc).get(2))));
        step(5, mc -> log("death: soldiers now " + soldiers(mc).size() + ", items on the ground nearby "
                + StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.distanceTo(mc.player) < 30)
                .map(e -> describe(((net.minecraft.world.entity.item.ItemEntity) e).getItem()) + " at " + e.blockPosition().toShortString()).toList()));

        // settings: /null config, slider one step left, Save
        step(20, mc -> command(mc, "null config"));
        step(5, mc -> {
            log("screen after /null config: " + screenName(mc));
            shot(mc, "n6_config");
        });
        step(5, mc -> {
            if (mc.screen != null) {
                mc.screen.children().stream().filter(w -> w instanceof AbstractSliderButton).findFirst().ifPresent(slider -> {
                    mc.screen.setFocused(slider);
                    slider.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
                });
            }
        });
        step(20, mc -> press(mc, "Save"));
        step(5, mc -> command(mc, "null config maxSoldiers 20"));

        // the anvil while the totem is held: refused, with a barrier saying who has it
        step(20, mc -> {
            command(mc, "setblock 0 -60 -27 anvil");
            command(mc, "give @s totem_of_undying");
            command(mc, "xp set @s 30 levels");
            at(mc, 0.5, -24.5, 180, 29);
        });
        step(10, mc -> mc.options.keyUse.setDown(true));
        step(15, mc -> mc.options.keyUse.setDown(false));
        step(10, Harness::putPlainTotemInAnvil);
        step(15, Harness::rename);
        step(5, mc -> {
            log("anvil while the totem is held: " + describe(result(mc)) + ", cost shown " + ((AnvilMenu) mc.player.containerMenu).getCost());
            shot(mc, "n7_anvil_taken");
            mc.player.closeContainer();
        });

        // pop, with a soldier on the hit list: it's erased, and every soldier returns to the void
        step(20, mc -> {
            tagged = soldiers(mc).isEmpty() ? "" : ((RemotePlayer) soldiers(mc).get(0)).getGameProfile().getName();
            command(mc, "null tag " + tagged);
            command(mc, "item replace entity @s weapon.mainhand with air"); // the plain totem: vanilla pops the main hand first
        });
        step(20, mc -> command(mc, "damage @s 100 minecraft:generic"));
        step(5, mc -> {
            log("soldiers seen after the pop: " + soldiers(mc).size() + ", health " + mc.player.getHealth() + " (tagged " + tagged + ")");
            shot(mc, "n8_pop");
            command(mc, "null untag " + tagged);
            command(mc, "give @s totem_of_undying");
        });

        // the anvil after the pop: makes the Totem of Null for 20 levels
        step(10, mc -> mc.options.keyUse.setDown(true));
        step(15, mc -> mc.options.keyUse.setDown(false));
        step(10, Harness::putPlainTotemInAnvil);
        step(15, Harness::rename);
        step(5, mc -> {
            log("anvil after the pop: " + describe(result(mc)) + ", cost " + ((AnvilMenu) mc.player.containerMenu).getCost());
            mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, AnvilMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, mc.player);
        });
        step(30, mc -> {
            mc.player.closeContainer();
            long totems = mc.player.getInventory().items.stream().filter(s -> describe(s).contains("Totem of Null")).count();
            log("after taking it: Totems of Null in inventory " + totems + ", levels " + mc.player.experienceLevel);
        });
        // only one: an outdated copy crumbles, a new /null give voids the old one, and an exact duplicate crumbles
        step(20, mc -> command(mc, "give @s totem_of_undying{nulltotem_serial:1}"));
        step(30, mc -> log("ownership: Totems of Null after an outdated copy was given " + totems(mc)));
        step(30, mc -> command(mc, "null give"));
        step(5, mc -> log("ownership: Totems of Null after a second /null give " + totems(mc)));
        step(30, mc -> {
            for (int slot = 0; slot < mc.player.getInventory().items.size(); slot++) {
                if (describe(mc.player.getInventory().items.get(slot)).contains("Totem of Null")) {
                    command(mc, "item replace entity @s hotbar.8 from entity @s container." + slot);
                    break;
                }
            }
        });
        step(5, mc -> log("ownership: Totems of Null after duplicating it " + totems(mc)));
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
        if (phase == 0) {
            if (++idle % 100 == 0) {
                log("waiting on screen " + screenName(mc));
                if (idle == 100) {
                    shot(mc, "n0_waiting");
                }
            }
            if (mc.screen instanceof TitleScreen && ++wait > 20) {
                log("connecting");
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString("localhost:25568"),
                        new ServerData("forge", "localhost:25568", false), false);
                phase = 1;
                wait = 0;
            }
            return;
        }
        if (phase == 1) {
            if (mc.player != null && mc.getConnection() != null && mc.screen == null && ++wait > 60) {
                log("joined");
                phase = 2;
                wait = 0;
            }
            return;
        }
        if (mc.player == null) {
            log("lost the connection");
            mc.stop();
            return;
        }
        if (mc.screen == null) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (next < steps.size()) {
            Step step = steps.get(next++);
            try {
                step.action().accept(mc);
            } catch (RuntimeException e) {
                log("step " + next + " failed: " + e);
            }
            wait = step.waitAfter();
        }
    }

    /** First call: remember each soldier's angle around the husk; second: how far the ones in reach circled. */
    private static void sampleAngles(Minecraft mc, boolean compare) {
        var husk = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> e instanceof net.minecraft.world.entity.monster.Husk).findFirst().orElse(null);
        if (husk == null) {
            log("no husk");
            return;
        }
        List<String> moved = new ArrayList<>();
        for (Entity e : soldiers(mc)) {
            double angle = Math.toDegrees(Math.atan2(e.getZ() - husk.getZ(), e.getX() - husk.getX()));
            if (compare && angles.containsKey(e.getId()) && e.distanceTo(husk) < 4) {
                double d = Math.abs(((angle - angles.get(e.getId())) % 360 + 540) % 360 - 180);
                moved.add(String.format("%.0f", d));
            }
            angles.put(e.getId(), angle);
        }
        if (compare) {
            log("circling: degrees moved around the husk in 5 s by soldiers in reach " + moved);
        }
    }

    private static String name(Entity soldier) {
        return ((RemotePlayer) soldier).getGameProfile().getName();
    }

    private static float health(Minecraft mc, String name) {
        return soldiers(mc).stream().filter(e -> name(e).equals(name)).map(e -> ((RemotePlayer) e).getHealth()).findFirst().orElse(-1f);
    }

    private static long totems(Minecraft mc) {
        return mc.player.getInventory().items.stream().filter(s -> describe(s).contains("Totem of Null")).count()
                + (describe(mc.player.getOffhandItem()).contains("Totem of Null") ? 1 : 0);
    }

    private static String screenName(Minecraft mc) {
        return mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
    }

    private static void press(Minecraft mc, String label) {
        if (mc.screen == null) {
            log("no screen to press \"" + label + "\" on");
            return;
        }
        mc.screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(label))
                .findFirst().ifPresentOrElse(w -> ((Button) w).onPress(), () -> log("no \"" + label + "\" button"));
    }

    private static List<Entity> soldiers(Minecraft mc) {
        return StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> e instanceof RemotePlayer && e.position().distanceTo(mc.player.position()) < 40).toList();
    }

    private static long count(Minecraft mc, Class<? extends Entity> type) {
        return StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> type.isInstance(e) && e.isAlive() && e.position().distanceTo(mc.player.position()) < 30).count();
    }

    private static List<String> distances(Minecraft mc, Vec3 from) {
        return soldiers(mc).stream().map(e -> String.format("%.1f", e.position().distanceTo(from))).toList();
    }

    private static void putPlainTotemInAnvil(Minecraft mc) {
        if (!(mc.player.containerMenu instanceof AnvilMenu anvil)) {
            log("no anvil open (screen " + screenName(mc) + ")");
            return;
        }
        for (Slot slot : anvil.slots) {
            ItemStack stack = slot.getItem();
            if (slot.index > AnvilMenu.RESULT_SLOT && stack.is(Items.TOTEM_OF_UNDYING) && !describe(stack).contains("Totem of Null")) {
                mc.gameMode.handleInventoryMouseClick(anvil.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
                return;
            }
        }
        log("no plain Totem of Undying to put in the anvil");
    }

    private static void rename(Minecraft mc) {
        if (mc.player.containerMenu instanceof AnvilMenu anvil) {
            anvil.setItemName("Totem of Null");
            mc.getConnection().send(new ServerboundRenameItemPacket("Totem of Null"));
        }
    }

    private static ItemStack result(Minecraft mc) {
        return mc.player.containerMenu instanceof AnvilMenu anvil ? anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem() : ItemStack.EMPTY;
    }

    private static String describe(ItemStack stack) {
        var display = stack.getTagElement("display");
        var lore = display == null ? null : display.getList("Lore", 8);
        String first = lore == null || lore.isEmpty() ? "" : " (" + net.minecraft.network.chat.Component.Serializer.fromJson(lore.getString(0)).getString() + ")";
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()) + " \"" + stack.getHoverName().getString() + "\"" + first;
    }

    private static void shot(Minecraft mc, String name) {
        String file = "forgetest_" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), component -> log("shot " + file));
    }

    private static void log(String message) {
        System.out.println("[FORGETEST] " + message);
    }
}
