package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Open screen container sync, slot click processing, and top-screen suppression for Forge 1.20.1.
 */
public final class ContainerRelayForge {

    public static final String CLICK_PREFIX = "SLOT:";
    public static final String WIDGET_CLICK_PREFIX = "WIDGET:";
    private static final int OUTSIDE_SLOT = -999;
    private static volatile boolean suppressRendering = true;
    private static ScreenHandlerState lastSent;

    public record SlotState(
            HudState.HotbarSlot stack,
            boolean mayPlace,
            int slotIndex,
            int x,
            int y
    ) {}

    public record WidgetState(
            int id,
            String label,
            String className,
            int x,
            int y,
            int width,
            int height,
            boolean active
    ) {}

    public record ScreenHandlerState(
            String type,
            int syncId,
            String handlerType,
            String title,
            boolean isScreenOpen,
            HudState.HotbarSlot cursor,
            List<SlotState> slots,
            List<WidgetState> widgets,
            int playerStart,
            int hotbarStart,
            int armorStart,
            int offhandIndex
    ) {}

    private ContainerRelayForge() {}

    public static void setSuppressRendering(boolean suppress) {
        suppressRendering = suppress;
    }

    public static boolean isSuppressRendering() {
        return suppressRendering;
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (suppressRendering && event.getScreen() instanceof AbstractContainerScreen<?>) {
            event.setCanceled(true);
        }
    }

    public static void invalidate() {
        lastSent = null;
    }

    public static void broadcastIfChanged(Minecraft client, HudStateServer server) {
        Player player = client.player;
        if (player == null) return;

        ScreenHandlerState state = capture(client, player);
        if (state.equals(lastSent)) return;

        lastSent = state;
        server.broadcastContainer(state);
    }

    public static ScreenHandlerState current() {
        return lastSent;
    }

    private static ScreenHandlerState capture(Minecraft client, Player player) {
        AbstractContainerMenu handler = player.containerMenu;
        int size = handler.slots.size();
        ItemStack cursor = handler.getCarried();

        List<SlotState> slots = new ArrayList<>(size);
        for (Slot slot : handler.slots) {
            slots.add(new SlotState(
                    HudState.slotFrom(slot.getItem()),
                    slot.mayPlace(cursor),
                    slot.index,
                    slot.x,
                    slot.y
            ));
        }

        boolean isPlayerInventory = handler instanceof InventoryMenu;
        boolean isScreenOpen = client.screen instanceof AbstractContainerScreen<?>;
        String title = client.screen != null ? client.screen.getTitle().getString() : null;

        List<WidgetState> widgets = new ArrayList<>();
        if (client.screen != null) {
            int widgetId = 0;
            for (var child : client.screen.children()) {
                if (child instanceof AbstractWidget widget && widget.visible) {
                    String label = widget.getMessage() != null ? widget.getMessage().getString() : "";
                    String className = widget.getClass().getSimpleName();
                    widgets.add(new WidgetState(
                            widgetId++,
                            label,
                            className,
                            widget.getX(),
                            widget.getY(),
                            widget.getWidth(),
                            widget.getHeight(),
                            widget.active
                    ));
                }
            }
        }

        return new ScreenHandlerState(
                "container",
                handler.containerId,
                handlerTypeOf(handler, isPlayerInventory),
                title,
                isScreenOpen,
                HudState.slotFrom(cursor),
                slots,
                widgets,
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

    public static void clickWidget(Minecraft client, int widgetId) {
        if (client.screen == null) return;
        client.execute(() -> {
            int cur = 0;
            for (var child : client.screen.children()) {
                if (child instanceof AbstractWidget widget && widget.visible) {
                    if (cur == widgetId) {
                        if (widget.active) {
                            widget.playDownSound(client.getSoundManager());
                            if (widget instanceof Button btn) {
                                btn.onPress();
                            } else {
                                widget.onClick(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
                            }
                        }
                        return;
                    }
                    cur++;
                }
            }
        });
    }

    public static void clickScreen(Minecraft client, double x, double y) {
        if (client.screen == null) return;
        client.execute(() -> {
            client.screen.mouseClicked(x, y, 0);
        });
    }

    public static void sortInventory(Minecraft client) {
        if (client.screen == null) return;
        client.execute(() -> {
            for (var child : client.screen.children()) {
                if (child instanceof AbstractWidget widget && widget.visible && widget.active) {
                    String name = widget.getClass().getSimpleName().toLowerCase();
                    String label = widget.getMessage() != null ? widget.getMessage().getString().toLowerCase() : "";
                    if (name.contains("sort") || label.contains("sort") || name.contains("miniinventorybutton")) {
                        widget.playDownSound(client.getSoundManager());
                        if (widget instanceof Button btn) {
                            btn.onPress();
                        } else {
                            widget.onClick(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
                        }
                        return;
                    }
                }
            }
        });
    }
}
