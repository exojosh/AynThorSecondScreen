package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Open screen container sync and slot click processing for Forge 1.20.1.
 */
public final class ContainerRelayForge {

    public static final String CLICK_PREFIX = "SLOT:";
    private static final int OUTSIDE_SLOT = -999;
    private static ScreenHandlerState lastSent;

    public record SlotState(HudState.HotbarSlot stack, boolean mayPlace) {}

    public record ScreenHandlerState(
            String type,
            int syncId,
            String handlerType,
            HudState.HotbarSlot cursor,
            List<SlotState> slots,
            int playerStart,
            int hotbarStart,
            int armorStart,
            int offhandIndex
    ) {}

    private ContainerRelayForge() {}

    public static void invalidate() {
        lastSent = null;
    }

    public static void broadcastIfChanged(Minecraft client, HudStateServer server) {
        Player player = client.player;
        if (player == null) return;

        ScreenHandlerState state = capture(player);
        if (state.equals(lastSent)) return;

        lastSent = state;
        server.broadcastContainer(state);
    }

    public static ScreenHandlerState current() {
        return lastSent;
    }

    private static ScreenHandlerState capture(Player player) {
        AbstractContainerMenu handler = player.containerMenu;
        int size = handler.slots.size();
        ItemStack cursor = handler.getCarried();

        List<SlotState> slots = new ArrayList<>(size);
        for (Slot slot : handler.slots) {
            slots.add(new SlotState(HudState.slotFrom(slot.getItem()), slot.mayPlace(cursor)));
        }

        boolean isPlayerInventory = handler instanceof InventoryMenu;

        return new ScreenHandlerState(
                "container",
                handler.containerId,
                handlerTypeOf(handler, isPlayerInventory),
                HudState.slotFrom(cursor),
                slots,
                isPlayerInventory ? 9 : Math.max(0, size - 36),
                isPlayerInventory ? 36 : Math.max(0, size - 9),
                isPlayerInventory ? 5 : -1,
                isPlayerInventory ? 45 : -1
        );
    }

    private static String handlerTypeOf(AbstractContainerMenu handler, boolean isPlayerInventory) {
        if (isPlayerInventory) return null;
        try {
            ResourceLocation id = BuiltInRegistries.MENU.getKey(handler.getType());
            return id == null ? null : id.toString();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    public static void click(Minecraft client, String args) {
        Player player = client.player;
        if (player == null || client.gameMode == null) return;

        String[] parts = args.split(",");
        if (parts.length != 4) {
            System.out.println("[ThorHud] Malformed slot click: " + args);
            return;
        }

        int syncId;
        int slotId;
        int button;
        ClickType action;
        try {
            syncId = Integer.parseInt(parts[0].trim());
            slotId = Integer.parseInt(parts[1].trim());
            button = Integer.parseInt(parts[2].trim());
            action = ClickType.valueOf(parts[3].trim());
        } catch (IllegalArgumentException e) {
            System.out.println("[ThorHud] Unparseable slot click '" + args + "': " + e);
            return;
        }

        AbstractContainerMenu handler = player.containerMenu;
        if (syncId != handler.containerId) {
            System.out.println("[ThorHud] Dropping slot click for handler " + syncId
                    + "; the open one is " + handler.containerId);
            return;
        }

        if (slotId != OUTSIDE_SLOT && (slotId < 0 || slotId >= handler.slots.size())) {
            System.out.println("[ThorHud] Slot " + slotId + " is out of range for handler "
                    + handler.containerId);
            return;
        }

        client.gameMode.handleInventoryMouseClick(handler.containerId, slotId, button, action, player);
        lastSent = null;
    }
}
