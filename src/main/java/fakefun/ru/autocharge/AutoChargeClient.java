package fakefun.ru.autocharge;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class AutoChargeClient implements ClientModInitializer {
    private record ItemUseSource(Hand hand, int slot) {
        private static final int NO_SLOT = -1;

        static ItemUseSource mainHand(int slot) {
            return new ItemUseSource(Hand.MAIN_HAND, slot);
        }

        static ItemUseSource offHand() {
            return new ItemUseSource(Hand.OFF_HAND, NO_SLOT);
        }

        static ItemUseSource hotbarSlot(int slot) {
            return new ItemUseSource(Hand.MAIN_HAND, slot);
        }

        boolean requiresSlotSwitch() {
            return slot != NO_SLOT;
        }
    }

    private static final int MAX_MOUSE_BUTTONS = 16;
    private static final boolean[] PREVIOUS_KEYS = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private static final boolean[] PREVIOUS_MOUSE = new boolean[MAX_MOUSE_BUTTONS];

    private BindState bindState = BindConfig.load();
    private boolean waitingForBind;
    private boolean captureArmed;
    private int actionCooldownTicks;
    private ItemUseSource pendingPearlUse;
    private ItemUseSource pendingChargeUse;
    private int pendingRestoreSlot = -1;
    private int pendingActionDelay = -1;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.trim().startsWith(".")) {
                return true;
            }

            return handleCommand(message);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("bind")
                        .then(ClientCommandManager.literal("add").executes(context -> {
                            startBindCapture();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(context -> {
                            clearBind();
                            return Command.SINGLE_SUCCESS;
                        }))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private boolean handleCommand(String rawMessage) {
        String message = rawMessage.trim();
        String lowered = message.toLowerCase(Locale.ROOT);

        if (lowered.equals(".bind add")) {
            startBindCapture();
            return false;
        }

        if (lowered.equals(".bind clear")) {
            clearBind();
            return false;
        }

        return true;
    }

    private void startBindCapture() {
        waitingForBind = true;
        captureArmed = false;
        sendClientMessage("Press any keyboard key or mouse button to set the bind.");
    }

    private void clearBind() {
        waitingForBind = false;
        captureArmed = false;
        bindState = BindState.unbound();
        BindConfig.save(bindState);
        sendClientMessage("Bind cleared.");
    }

    private void onEndTick(MinecraftClient client) {
        if (actionCooldownTicks > 0) {
            actionCooldownTicks--;
        }

        if (client.player == null || client.world == null) {
            resetPendingActions();
            updatePreviousInput(client);
            return;
        }

        processPendingActions(client);

        if (waitingForBind) {
            if (tryCaptureBind(client)) {
                updatePreviousInput(client);
                return;
            }
        } else if (bindJustPressed(client) && actionCooldownTicks == 0 && !hasPendingActions()) {
            performPearlBoost(client);
        }

        updatePreviousInput(client);
    }

    private boolean tryCaptureBind(MinecraftClient client) {
        long handle = client.getWindow().getHandle();

        if (!captureArmed) {
            captureArmed = !isAnyInputPressed(handle);
            return false;
        }

        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            boolean pressed = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
            if (pressed && !PREVIOUS_KEYS[key]) {
                bindState = new BindState(BindType.KEYBOARD, key);
                waitingForBind = false;
                captureArmed = false;
                BindConfig.save(bindState);
                sendClientMessage("Bind set: " + describeBind(bindState));
                return true;
            }
        }

        for (int button = 0; button < MAX_MOUSE_BUTTONS; button++) {
            boolean pressed = GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
            if (pressed && !PREVIOUS_MOUSE[button]) {
                bindState = new BindState(BindType.MOUSE, button);
                waitingForBind = false;
                captureArmed = false;
                BindConfig.save(bindState);
                sendClientMessage("Bind set: " + describeBind(bindState));
                return true;
            }
        }

        return false;
    }

    private boolean bindJustPressed(MinecraftClient client) {
        if (!bindState.isBound() || client.currentScreen != null) {
            return false;
        }

        long handle = client.getWindow().getHandle();
        return switch (bindState.type()) {
            case KEYBOARD -> {
                boolean pressed = GLFW.glfwGetKey(handle, bindState.code()) == GLFW.GLFW_PRESS;
                yield pressed && !PREVIOUS_KEYS[bindState.code()];
            }
            case MOUSE -> {
                boolean pressed = GLFW.glfwGetMouseButton(handle, bindState.code()) == GLFW.GLFW_PRESS;
                yield pressed && !PREVIOUS_MOUSE[bindState.code()];
            }
            default -> false;
        };
    }

    private void performPearlBoost(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || player.getAbilities().flying) {
            return;
        }

        ItemUseSource pearlUse = findItemUseSource(player, Items.ENDER_PEARL);
        ItemUseSource chargeUse = findItemUseSource(player, Items.WIND_CHARGE);

        if (pearlUse == null || chargeUse == null) {
            if (pearlUse == null && chargeUse == null) {
                sendClientMessage("No ender pearl and wind charge found in hands or hotbar.");
            } else if (pearlUse == null) {
                sendClientMessage("No ender pearl found in hands or hotbar.");
            } else {
                sendClientMessage("No wind charge found in hands or hotbar.");
            }
            actionCooldownTicks = 10;
            return;
        }

        int oldSlot = player.getInventory().selectedSlot;
        pendingPearlUse = pearlUse;
        pendingChargeUse = chargeUse;
        pendingRestoreSlot = pearlUse.requiresSlotSwitch() || chargeUse.requiresSlotSwitch() ? oldSlot : -1;
        pendingActionDelay = 0;
        actionCooldownTicks = 8;
    }

    private void useItemSource(MinecraftClient client, ClientPlayerEntity player, ItemUseSource source) {
        if (source.requiresSlotSwitch()) {
            selectHotbarSlot(player, source.slot());
        }
        client.interactionManager.interactItem(player, source.hand());
        player.swingHand(source.hand());
    }

    private void processPendingActions(MinecraftClient client) {
        if (!hasPendingActions()) {
            return;
        }

        if (pendingActionDelay > 0) {
            pendingActionDelay--;
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            resetPendingActions();
            return;
        }

        if (pendingPearlUse != null) {
            useItemSource(client, player, pendingPearlUse);
            pendingPearlUse = null;
            pendingActionDelay = 1;
            return;
        }

        if (pendingChargeUse != null) {
            useItemSource(client, player, pendingChargeUse);
            pendingChargeUse = null;
            pendingActionDelay = 0;
            return;
        }

        if (pendingRestoreSlot != -1) {
            selectHotbarSlot(player, pendingRestoreSlot);
            pendingRestoreSlot = -1;
            pendingActionDelay = -1;
        }
    }

    private boolean hasPendingActions() {
        return pendingPearlUse != null || pendingChargeUse != null || pendingRestoreSlot != -1;
    }

    private void resetPendingActions() {
        pendingPearlUse = null;
        pendingChargeUse = null;
        pendingRestoreSlot = -1;
        pendingActionDelay = -1;
    }

    private void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private int findHotbarSlot(ClientPlayerEntity player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private ItemUseSource findItemUseSource(ClientPlayerEntity player, Item item) {
        if (player.getMainHandStack().isOf(item)) {
            return ItemUseSource.mainHand(player.getInventory().selectedSlot);
        }

        if (player.getOffHandStack().isOf(item)) {
            return ItemUseSource.offHand();
        }

        int slot = findHotbarSlot(player, item);
        if (slot != -1) {
            return ItemUseSource.hotbarSlot(slot);
        }

        return null;
    }

    private void updatePreviousInput(MinecraftClient client) {
        long handle = client.getWindow().getHandle();

        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            PREVIOUS_KEYS[key] = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
        }

        for (int button = 0; button < MAX_MOUSE_BUTTONS; button++) {
            PREVIOUS_MOUSE[button] = GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
        }
    }

    private boolean isAnyInputPressed(long handle) {
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS) {
                return true;
            }
        }

        for (int button = 0; button < MAX_MOUSE_BUTTONS; button++) {
            if (GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS) {
                return true;
            }
        }

        return false;
    }

    private String describeBind(BindState state) {
        return switch (state.type()) {
            case KEYBOARD -> GLFW.glfwGetKeyName(state.code(), 0) != null
                    ? GLFW.glfwGetKeyName(state.code(), 0).toUpperCase(Locale.ROOT)
                    : "KEY_" + state.code();
            case MOUSE -> "MOUSE_" + (state.code() + 1);
            default -> "NONE";
        };
    }

    private void sendClientMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[AutoCharge] " + message), false);
        }
    }
}
