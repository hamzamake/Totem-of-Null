package dev.nulltotem;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Client side: the totem's sneak + left-click, the orders menu and the settings window. */
@EventBusSubscriber(modid = NullTotemMod.MOD_ID, value = Dist.CLIENT)
public final class NullClient {
    private static boolean clickHeld;

    /**
     * Sneak + left-click with the Totem of Null is an order, not an attack: it goes to the server as a TotemClick, the
     * hand swings (the cue that it registered), and nothing is hit or mined until the button is let go.
     */
    @SubscribeEvent
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!event.isAttack() || player == null || !player.isShiftKeyDown()) {
            return;
        }
        InteractionHand hand = Orders.totemHand(player);
        if (hand == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!clickHeld) {
            clickHeld = true;
            PacketDistributor.sendToServer(new Payloads.TotemClick());
            player.swing(hand);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (clickHeld && !Minecraft.getInstance().options.keyAttack.isDown()) {
            clickHeld = false;
        }
    }

    static void openOrders(String status) {
        Minecraft.getInstance().setScreen(new OrdersScreen(status));
    }

    static void openConfig(int maxSoldiers) {
        Minecraft.getInstance().setScreen(new ConfigScreen(maxSoldiers));
    }

    private static void run(String command) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(command);
        }
    }

    /** One button per order, in two columns, under the soldiers' status and the totem's controls. */
    static final class OrdersScreen extends Screen {
        private static final int BUTTON_W = 150;
        private static final int BUTTON_H = 20;
        private static final int GAP = 4;
        private static final int LINE = 10;
        private static final String[][] ORDERS = {
                {"Follow me", "Stop whatever they're doing and walk with you", "follow"},
                {"Attack what I look at", "A mob up to " + Orders.RANGE + " blocks away; it glows", "attack"},
                {"Mine blocks like this", "Every block like the one you look at, within 16 blocks of it. The drops go to you", "mine"},
                {"Bridge to where I look", "A soldier builds a netherrack bridge, up to 64 blocks", "bridge"},
                {"Summon soldiers", "Up to your max, out of a nether portal", "summon"},
                {"Dismiss soldiers", "They return to the void", "dismiss"}};
        private static final List<String> CONTROLS = List.of(
                "Sneak + right-click: off hand summons, main hand opens this menu",
                "Sneak + left-click, off hand: send them where you look (again: call back)",
                "Sneak + left-click, main hand: netherrack bridge");

        private final String status;
        private final List<FormattedCharSequence> statusLines = new ArrayList<>();
        private final List<FormattedCharSequence> controlLines = new ArrayList<>();
        private int textTop;

        OrdersScreen(String status) {
            super(Component.literal("Null Soldier Orders"));
            this.status = status;
        }

        @Override
        protected void init() {
            int textWidth = width - 20;
            statusLines.clear();
            statusLines.addAll(font.split(Component.literal(status), textWidth));
            controlLines.clear();
            for (String line : CONTROLS) {
                controlLines.addAll(font.split(Component.literal(line).withStyle(ChatFormatting.GRAY), textWidth));
            }
            int rows = (ORDERS.length + 1) / 2;
            int textHeight = (statusLines.size() + controlLines.size()) * LINE + 6;
            int total = LINE + 8 + textHeight + 8 + rows * (BUTTON_H + GAP) + GAP + BUTTON_H;
            int top = Math.max(6, (height - total) / 2);
            textTop = top + LINE + 8;
            int buttonsTop = textTop + textHeight + 8;
            int left = width / 2 - BUTTON_W - GAP / 2;
            for (int i = 0; i < ORDERS.length; i++) {
                String[] order = ORDERS[i];
                int x = left + (i % 2) * (BUTTON_W + GAP);
                int y = buttonsTop + (i / 2) * (BUTTON_H + GAP);
                addRenderableWidget(Button.builder(Component.literal(order[0]), b -> {
                            onClose();
                            run("null order " + order[2]);
                        })
                        .bounds(x, y, BUTTON_W, BUTTON_H).tooltip(Tooltip.create(Component.literal(order[1]))).build());
            }
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(width / 2 - BUTTON_W / 2, buttonsTop + rows * (BUTTON_H + GAP) + GAP, BUTTON_W, BUTTON_H).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, title, width / 2, textTop - LINE - 8, 0xFFFFFF);
            int y = textTop;
            for (FormattedCharSequence line : statusLines) {
                graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
                y += LINE;
            }
            y += 6;
            for (FormattedCharSequence line : controlLines) {
                graphics.drawCenteredString(font, line, width / 2, y, 0xAAAAAA);
                y += LINE;
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** The settings window: a slider for the max soldiers per player; Save runs /null config maxSoldiers. */
    static final class ConfigScreen extends Screen {
        private static final int WIDTH = 200;
        private int value;

        ConfigScreen(int current) {
            super(Component.literal("Totem of Null Settings"));
            this.value = current;
        }

        @Override
        protected void init() {
            int top = Math.max(30, height / 2 - 40);
            int left = width / 2 - WIDTH / 2;
            addRenderableWidget(new AbstractSliderButton(left, top, WIDTH, 20, Component.empty(),
                    (value - 1) / (double) (NullState.HARD_CAP - 1)) {
                {
                    updateMessage();
                }

                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Max soldiers per player: " + current()));
                }

                @Override
                protected void applyValue() {
                    ConfigScreen.this.value = current(); // (a bare "value" here is the slider's own 0..1 field)
                }

                // arrow keys move one soldier at a time (vanilla moves a pixel, about a tenth of a step here)
                @Override
                public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                    if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
                        int next = Mth.clamp(current() + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1), 1, NullState.HARD_CAP);
                        this.value = (next - 1) / (double) (NullState.HARD_CAP - 1);
                        applyValue();
                        updateMessage();
                        return true;
                    }
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }

                private int current() {
                    return 1 + (int) Math.round(Mth.clamp(this.value, 0, 1) * (NullState.HARD_CAP - 1));
                }
            }).setTooltip(Tooltip.create(Component.literal("Lower this if 20 fake players is too heavy for the server")));
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                run("null config maxSoldiers " + value);
                onClose();
            }).bounds(left, top + 28, WIDTH / 2 - 2, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(left + WIDTH / 2 + 2, top + 28, WIDTH / 2 - 2, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            int top = Math.max(30, height / 2 - 40);
            graphics.drawCenteredString(font, title, width / 2, top - 20, 0xFFFFFF);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
