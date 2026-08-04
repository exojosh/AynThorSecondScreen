package com.exojosh.client;

//import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Client-only entrypoint. Jobs:
 *   1. Remove the vanilla HUD elements we're moving to the second screen.
 *   2. Every client tick, snapshot player state and push it to HudStateServer.
 *   3. Drain incoming commands from the companion app -- either an icon
 *      request (handled here directly, since it's a query/response thing,
 *      not a game action) or an action command (handed off to
 *      CommandDispatcher, which simulates the matching keypress).
 */
public class ThorHudClient implements ClientModInitializer {

    private static final String ICON_REQUEST_PREFIX = "ICON:";

    // Loopback-only. Nothing here ever needs to leave the device.
    public static final HudStateServer HUD_SERVER = new HudStateServer(48291);

    @Override
    public void onInitializeClient() {
        HudElementRegistry.removeElement(VanillaHudElements.HOTBAR);
        HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.FOOD_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.EXPERIENCE_LEVEL);
        HudElementRegistry.removeElement(VanillaHudElements.INFO_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.ARMOR_BAR);

        HUD_SERVER.start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        // Release anything simulated-pressed on the previous tick before
        // considering new commands, so a press always lasts exactly one tick.
        CommandDispatcher.tick();

        PlayerEntity player = client.player;
        if (player == null) return;

        HudState state = new HudState(
                player.getHealth(),
                player.getMaxHealth(),
                player.getArmor(),
                player.getHungerManager().getFoodLevel(),
                player.experienceLevel,
                player.experienceProgress,
                player.getInventory().getSelectedSlot(),
                HudState.hotbarFromInventory(player)
        );

        HUD_SERVER.broadcast(state);

        String incoming;
        while ((incoming = HUD_SERVER.pollCommand()) != null) {
            if (incoming.startsWith(ICON_REQUEST_PREFIX)) {
                handleIconRequest(incoming.substring(ICON_REQUEST_PREFIX.length()));
            } else {
                CommandDispatcher.dispatch(incoming);
            }
        }
    }

    private void handleIconRequest(String itemId) {
        ItemIconResolver.resolveBase64Png(itemId)
                .ifPresent(base64Png -> HUD_SERVER.broadcastIcon(itemId, base64Png));
        // If resolution fails, we just don't respond -- the companion app's
        // request will simply go unanswered for that item rather than
        // erroring, which is fine for a first pass.
    }
}
