package com.exojosh.client;

//import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

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

    /** Asks for the HUD texture bundle to be re-sent (e.g. after a resource
     *  pack change). New connections get it automatically without asking. */
    private static final String ASSET_REQUEST = "ASSETS";

    /**
     * Cap how many icons we render per tick. Each one is an offscreen draw
     * plus a fenced GPU readback; a freshly-connected app asks for up to 9 at
     * once and there's no reason to do them all in a single frame.
     */
    private static final int MAX_ICON_RENDERS_PER_TICK = 2;

    /**
     * Ticks between map tile renders. 10 is ~2/sec, which reads as live while
     * walking without re-scanning 16k columns every frame. The tile is only
     * rendered when a client is actually connected.
     */
    private static final int MAP_UPDATE_INTERVAL_TICKS = 10;

    private int ticksSinceMapUpdate = MAP_UPDATE_INTERVAL_TICKS;

    // Loopback-only. Nothing here ever needs to leave the device.
    public static final HudStateServer HUD_SERVER = new HudStateServer(48291);

    private final Queue<String> iconRequestQueue = new ArrayDeque<>();

    /**
     * Items we've queued or are mid-render on. Collapses duplicate requests
     * (the app re-asks on retry) without permanently blocking a retry the way
     * a plain "already requested" set would -- entries come out again as soon
     * as we've answered, success or failure.
     */
    private final Set<String> inFlightIcons = new HashSet<>();

    @Override
    public void onInitializeClient() {
        HudElementRegistry.removeElement(VanillaHudElements.HOTBAR);
        HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.FOOD_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.AIR_BAR);
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
                player.getAbsorptionAmount(),
                player.getArmor(),
                player.getHungerManager().getFoodLevel(),
                player.experienceLevel,
                player.experienceProgress,
                player.getInventory().getSelectedSlot(),
                player.getAir(),
                player.getMaxAir(),
                HudState.hotbarFromInventory(player),
                HudState.offhandFrom(player)
        );

        HUD_SERVER.broadcast(state);

        String incoming;
        while ((incoming = HUD_SERVER.pollCommand()) != null) {
            if (incoming.startsWith(ICON_REQUEST_PREFIX)) {
                queueIconRequest(incoming.substring(ICON_REQUEST_PREFIX.length()));
            } else if (incoming.equals(ASSET_REQUEST)) {
                // Explicit re-request: the app asks for this after the player
                // changes resource packs, since we have no reload hook.
                broadcastHudAssets();
            } else {
                CommandDispatcher.dispatch(incoming);
            }
        }

        pushAssetsToNewClients();
        drainIconQueue(client);
        maybeBroadcastMap(client);
    }

    /**
     * Renders and ships a map tile every MAP_UPDATE_INTERVAL_TICKS.
     *
     * Skipped entirely when nothing is connected: this is the most expensive
     * thing in the tick loop, and on a handheld there's no reason to pay for
     * it when the second screen isn't watching.
     */
    private void maybeBroadcastMap(MinecraftClient client) {
        if (!HUD_SERVER.hasClients()) {
            // Reset so a freshly-connected app gets a tile on its first tick
            // rather than waiting out the remainder of an interval.
            ticksSinceMapUpdate = MAP_UPDATE_INTERVAL_TICKS;
            return;
        }

        if (++ticksSinceMapUpdate < MAP_UPDATE_INTERVAL_TICKS) return;
        ticksSinceMapUpdate = 0;

        MapRenderer.render(client).ifPresent(HUD_SERVER::broadcastMap);
    }

    /**
     * Ships the whole HUD texture set to each newly-connected app.
     *
     * Done here rather than on the accept thread because resolving textures
     * goes through the resource manager, which is client-thread state. Running
     * once per new connection (not per tick) keeps this off the hot path --
     * it's ~17 small PNGs, a few tens of KB in total.
     */
    private void pushAssetsToNewClients() {
        Socket client;
        while ((client = HUD_SERVER.pollNewClient()) != null) {
            for (String key : HudAssetCatalog.keys()) {
                HUD_SERVER.sendAssetTo(client, key, HudAssetCatalog.resolveBase64Png(key).orElse(null));
            }
            System.out.println("[ThorHud] Sent HUD asset bundle to " + client.getRemoteSocketAddress());
        }
    }

    private void broadcastHudAssets() {
        for (String key : HudAssetCatalog.keys()) {
            HUD_SERVER.broadcastAsset(key, HudAssetCatalog.resolveBase64Png(key).orElse(null));
        }
    }

    private void queueIconRequest(String itemId) {
        if (itemId.isEmpty() || itemId.equals("minecraft:air")) return;
        if (inFlightIcons.add(itemId)) {
            iconRequestQueue.add(itemId);
        }
    }

    private void drainIconQueue(MinecraftClient client) {
        for (int i = 0; i < MAX_ICON_RENDERS_PER_TICK; i++) {
            String itemId = iconRequestQueue.poll();
            if (itemId == null) return;
            renderAndSendIcon(client, itemId);
        }
    }

    /**
     * Renders the item through the real model pipeline (isometric, correct
     * per-face textures, correct tints) and ships it. Falls back to the raw
     * texture-file resolver if the render can't produce anything, and if even
     * that fails, sends an explicit failure so the app stops waiting on a
     * reply that's never coming -- previously a failed resolve just silently
     * dropped the request, which is what made icon delivery look flaky.
     */
    private void renderAndSendIcon(MinecraftClient client, String itemId) {
        ItemStack stack = resolveStack(client, itemId);
        if (stack == null || stack.isEmpty()) {
            inFlightIcons.remove(itemId);
            HUD_SERVER.broadcastIconFailure(itemId);
            return;
        }

        ItemIconRenderer.renderBase64Png(stack, base64Png -> {
            inFlightIcons.remove(itemId);

            if (base64Png != null) {
                HUD_SERVER.broadcastIcon(itemId, base64Png);
                return;
            }

            Optional<String> fallback = ItemIconResolver.resolveBase64Png(itemId);
            if (fallback.isPresent()) {
                HUD_SERVER.broadcastIcon(itemId, fallback.get());
            } else {
                HUD_SERVER.broadcastIconFailure(itemId);
            }
        });
    }

    /**
     * Prefer the player's actual stack for this item so component-driven
     * rendering (dyed leather, potion colour, damage-based models) matches
     * what they're really holding. Falls back to a plain default stack for
     * anything not currently in the inventory.
     */
    private ItemStack resolveStack(MinecraftClient client, String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;

        PlayerEntity player = client.player;
        if (player != null) {
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack candidate = player.getInventory().getStack(slot);
                if (!candidate.isEmpty() && Registries.ITEM.getId(candidate.getItem()).equals(id)) {
                    return candidate;
                }
            }
        }

        return Registries.ITEM.getOptionalValue(id).map(ItemStack::new).orElse(null);
    }
}
