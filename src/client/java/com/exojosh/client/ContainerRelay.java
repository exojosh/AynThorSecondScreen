package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The open screen handler's contents, and clicks back into it.
 *
 * <h2>Why the handler and not the inventory</h2>
 * The obvious thing to stream is {@code player.getInventory()}. It's the wrong
 * object: moving an item is not a write to the inventory, it's a *click on a
 * slot*, and the server only accepts those against the handler it thinks is
 * open. Going through {@code currentScreenHandler} means the second screen and
 * the game agree on what's being clicked, and it costs nothing — with no screen
 * open, {@code currentScreenHandler} already *is* the player's own inventory
 * handler.
 *
 * <h2>Sent on change, not every tick</h2>
 * Unlike the HUD snapshot, this is 46+ slots and it is almost always identical
 * to the last one — a player walking around changes nothing here for minutes at
 * a time. The whole state is rebuilt each tick (cheap: it's a record of records)
 * and only written to the socket when it differs.
 *
 * Structural equality is the comparison, which means two stacks that differ only
 * in something the wire format can't express (custom name, NBT beyond damage)
 * read as equal and don't re-send. That's the right trade for now: the app can't
 * draw the difference either.
 *
 * <h2>Slot roles</h2>
 * The app is told where the player's own inventory starts rather than being left
 * to derive it. For {@link PlayerScreenHandler} the indices are vanilla's named
 * constants; for anything else the player's 36 slots are the *last* 36, which is
 * a convention every vanilla container follows. Sending it means the app never
 * has to know that convention, and a handler that breaks it only needs fixing
 * here.
 */
public final class ContainerRelay {

    /** Clicks a slot: {@code SLOT:<syncId>,<slotId>,<button>,<ACTION>}. */
    public static final String CLICK_PREFIX = "SLOT:";

    /**
     * Vanilla's "clicked outside the window" slot id, which drops the held
     * stack. Passed straight through rather than special-cased, because the
     * server already understands it.
     */
    private static final int OUTSIDE_SLOT = -999;

    private static ScreenHandlerState lastSent;

    /**
     * One slot's contents plus whether the player may put something there.
     *
     * {@code mayPlace} is the handler's own {@code canInsert}, so the app can
     * grey out a furnace's output or an armor slot that won't take a sword,
     * rather than letting the player make a move the server will reject.
     */
    public record SlotState(HudState.HotbarSlot stack, boolean mayPlace) {
    }

    public record ScreenHandlerState(
            String type,
            int syncId,
            /** Registry id of the open container, or null for the player's own
             *  inventory — which has no type at all (see {@link #handlerTypeOf}). */
            String handlerType,
            /** What's "on the mouse". Empty most of the time; non-empty means a
             *  move is half-finished and the app must show it, or the player
             *  has no way to know where their item went. */
            HudState.HotbarSlot cursor,
            List<SlotState> slots,
            int playerStart,
            int hotbarStart,
            /** -1 when the handler has no armor slots, i.e. anything but the
             *  player's own inventory. */
            int armorStart,
            /** -1 for the same reason. */
            int offhandIndex
    ) {
    }

    private ContainerRelay() {
    }

    /** Drops the cached copy so the next tick re-sends. Call on disconnect, or
     *  the first client to reconnect sees nothing until something changes. */
    public static void invalidate() {
        lastSent = null;
    }

    /** Builds the current state and broadcasts it if it differs from the last. */
    public static void broadcastIfChanged(MinecraftClient client, HudStateServer server) {
        PlayerEntity player = client.player;
        if (player == null) return;

        ScreenHandlerState state = capture(player);
        if (state.equals(lastSent)) return;

        lastSent = state;
        server.broadcastContainer(state);
    }

    /** The last state sent, for the backlog a newly-connected app gets. */
    public static ScreenHandlerState current() {
        return lastSent;
    }

    private static ScreenHandlerState capture(PlayerEntity player) {
        ScreenHandler handler = player.currentScreenHandler;
        int size = handler.slots.size();

        // Tested against what's actually on the cursor, because that's what
        // decides whether the next tap does anything. With an empty cursor most
        // slots say yes, which is correct and uninformative; the cases worth
        // drawing differently are holding a sword over an armor slot, or any
        // crafting/furnace *result* slot, which never accepts anything.
        ItemStack cursor = handler.getCursorStack();

        List<SlotState> slots = new ArrayList<>(size);
        for (Slot slot : handler.slots) {
            slots.add(new SlotState(HudState.slotFrom(slot.getStack()), slot.canInsert(cursor)));
        }

        boolean isPlayerInventory = handler instanceof PlayerScreenHandler;

        return new ScreenHandlerState(
                "container",
                handler.syncId,
                handlerTypeOf(handler, isPlayerInventory),
                HudState.slotFrom(cursor),
                slots,
                isPlayerInventory ? PlayerScreenHandler.INVENTORY_START : Math.max(0, size - 36),
                isPlayerInventory ? PlayerScreenHandler.HOTBAR_START : Math.max(0, size - 9),
                isPlayerInventory ? PlayerScreenHandler.EQUIPMENT_START : -1,
                isPlayerInventory ? PlayerScreenHandler.OFFHAND_ID : -1
        );
    }

    /**
     * {@code ScreenHandler.getType()} <em>throws</em> when the handler was built
     * without a type, and the player's own inventory handler is exactly that
     * case — it's never opened by type, so vanilla passes null. Asking it
     * unguarded would blow up every tick the player isn't in a container.
     */
    private static String handlerTypeOf(ScreenHandler handler, boolean isPlayerInventory) {
        if (isPlayerInventory) return null;
        try {
            Identifier id = Registries.SCREEN_HANDLER.getId(handler.getType());
            return id == null ? null : id.toString();
        } catch (UnsupportedOperationException e) {
            // A modded handler built without a type. Not fatal: the app falls
            // back to its generic layout, which is what it would use anyway.
            return null;
        }
    }

    /**
     * Performs a slot click, from {@code SLOT:<syncId>,<slotId>,<button>,<ACTION>}.
     *
     * <h2>Why the app sends a syncId it can't be sure of</h2>
     * It's a staleness check, not information we need — the handler is right
     * here. Between the app drawing a chest's slots and the player's finger
     * landing, the chest can close, and {@code currentScreenHandler} silently
     * becomes the player's inventory with an entirely different meaning for
     * "slot 3". Vanilla's {@code clickSlot} catches the mismatch too, but only
     * after it has already mutated the client-side handler; refusing here keeps
     * the two ends consistent.
     */
    public static void click(MinecraftClient client, String args) {
        PlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        String[] parts = args.split(",");
        if (parts.length != 4) {
            System.out.println("[ThorHud] Malformed slot click: " + args);
            return;
        }

        int syncId;
        int slotId;
        int button;
        SlotActionType action;
        try {
            syncId = Integer.parseInt(parts[0].trim());
            slotId = Integer.parseInt(parts[1].trim());
            button = Integer.parseInt(parts[2].trim());
            action = SlotActionType.valueOf(parts[3].trim());
        } catch (IllegalArgumentException e) {
            System.out.println("[ThorHud] Unparseable slot click '" + args + "': " + e);
            return;
        }

        ScreenHandler handler = player.currentScreenHandler;
        if (syncId != handler.syncId) {
            System.out.println("[ThorHud] Dropping slot click for handler " + syncId
                    + "; the open one is " + handler.syncId);
            return;
        }

        if (slotId != OUTSIDE_SLOT && (slotId < 0 || slotId >= handler.slots.size())) {
            System.out.println("[ThorHud] Slot " + slotId + " is out of range for handler "
                    + handler.syncId);
            return;
        }

        client.interactionManager.clickSlot(handler.syncId, slotId, button, action, player);

        // The click mutates the handler in place; re-send on the next tick
        // rather than waiting for the comparison to notice, so the second
        // screen reflects the move immediately instead of a tick late.
        lastSent = null;
    }
}
