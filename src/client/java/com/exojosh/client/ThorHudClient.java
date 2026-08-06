package com.exojosh.client;

//import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
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
     * Asks for the key-binding list to be re-sent. New connections get it
     * unprompted; the app re-asks when it opens its input picker, because a
     * player who rebinds a key mid-session leaves the printed key names stale
     * and there's no rebind hook to push from.
     */
    private static final String BINDING_REQUEST = "BINDINGS";

    /**
     * Which HUD elements the *game* should draw, as a comma-separated list of
     * the app's element keys -- i.e. the ones the player switched off on the
     * second screen, handed back to the main display. Full state every time,
     * so the two screens can't drift into drawing an element twice or not at
     * all. See {@link GameHudVisibility}.
     */
    private static final String HUD_VISIBILITY_PREFIX = "HUD:";

    /**
     * A line of chat to say as the player, e.g. {@code CHAT:hello} or
     * {@code CHAT:/time set day}.
     *
     * Handled here rather than in {@link CommandDispatcher} for a reason that
     * isn't just tidiness: the dispatcher drops every command while a screen is
     * open, because vanilla wouldn't have drained a keybinding then either.
     * Chat doesn't go through a KeyBinding at all -- it's a packet -- so that
     * reasoning doesn't apply, and a player with their inventory open should
     * still be able to answer someone.
     */
    private static final String CHAT_PREFIX = "CHAT:";

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

    /**
     * Whether a player existed on the previous tick, so leaving a world can be
     * detected as a *transition* rather than as a state.
     *
     * Without this the tick loop simply returned early with no player, which
     * left the second screen showing the last snapshot forever — full health,
     * a hotbar, a map of wherever you last stood — while the game sat at the
     * main menu. Nothing about that reads as "not in a world"; it reads as a
     * frozen app.
     */
    private boolean hadPlayer = false;

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
        // Every element the second screen can draw is wrapped rather than
        // removed outright, so the app can hand one back to the game later.
        // Nothing is shown in game until it asks, which matches what the old
        // unconditional removeElement() calls did.
        GameHudVisibility.install();

        // Registers the receive-message hooks. Done before the server starts
        // so nothing that arrives during startup is missed, and so the backlog
        // exists by the time the first client connects.
        ChatRelay.install(HUD_SERVER);

        HUD_SERVER.start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        // Release anything simulated-pressed on the previous tick before
        // considering new commands, so a press always lasts exactly one tick.
        CommandDispatcher.tick();

        PlayerEntity player = client.player;
        if (player == null) {
            onLeftWorld();
            return;
        }
        hadPlayer = true;

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
            } else if (incoming.equals(BINDING_REQUEST)) {
                HUD_SERVER.broadcastBindings(KeyBindingCatalog.snapshot());
            } else if (incoming.startsWith(HUD_VISIBILITY_PREFIX)) {
                GameHudVisibility.setShownInGame(
                        incoming.substring(HUD_VISIBILITY_PREFIX.length()));
            } else if (incoming.startsWith(CHAT_PREFIX)) {
                ChatRelay.send(client, incoming.substring(CHAT_PREFIX.length()));
            } else if (incoming.startsWith(ContainerRelay.CLICK_PREFIX)) {
                // Also outside CommandDispatcher, and for a stronger reason
                // than chat: a slot click is only meaningful *while* a screen
                // is open, which is exactly when the dispatcher drops things.
                ContainerRelay.click(client, incoming.substring(ContainerRelay.CLICK_PREFIX.length()));
            } else {
                CommandDispatcher.dispatch(incoming);
            }
        }

        pushBundleToNewClients();
        drainIconQueue(client);
        maybeBroadcastMap(client);

        // Cheap when nothing has moved -- it rebuilds the state and compares,
        // and only touches the socket on a difference. Still skipped with no
        // client, on the same reasoning as the map.
        if (HUD_SERVER.hasClients()) {
            ContainerRelay.broadcastIfChanged(client, HUD_SERVER);
        }
    }

    /**
     * Tells the app the player is gone — main menu, world unloaded, kicked.
     *
     * Sent once on the transition rather than every tick: it's a latch, and the
     * app holds "no player" until a real snapshot arrives. Repeating it 20
     * times a second at the main menu would be the same unthrottled broadcast
     * this replaces.
     *
     * The chat backlog goes with it, matching vanilla — {@code ChatHud} is
     * cleared on disconnect, so keeping it here would show the previous
     * world's conversation over the next one's.
     */
    private void onLeftWorld() {
        if (!hadPlayer) return;
        hadPlayer = false;

        ChatRelay.clearHistory();
        // So re-entering a world re-sends the inventory rather than waiting for
        // the first thing the player moves.
        ContainerRelay.invalidate();
        ticksSinceMapUpdate = MAP_UPDATE_INTERVAL_TICKS;

        HUD_SERVER.broadcastNoPlayer();
        System.out.println("[ThorHud] No player -- told the second screen to wait");
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
     * Ships everything a newly-connected app needs up front: the whole HUD
     * texture set, then the key-binding list.
     *
     * Done here rather than on the accept thread because both go through
     * client-thread state -- textures through the resource manager, bindings
     * through GameOptions and the language files. Running once per new
     * connection (not per tick) keeps it off the hot path: ~17 small PNGs and
     * a hundred-odd short strings, a few tens of KB in total.
     */
    private void pushBundleToNewClients() {
        Socket client;
        while ((client = HUD_SERVER.pollNewClient()) != null) {
            for (String key : HudAssetCatalog.keys()) {
                HUD_SERVER.sendAssetTo(client, key, HudAssetCatalog.resolveBase64Png(key).orElse(null));
            }
            HUD_SERVER.sendBindingsTo(client, KeyBindingCatalog.snapshot());

            // Chat backlog last, so it lands after the font sheet it needs to
            // be drawn with. Nothing re-sends chat on its own, so without this
            // an app restarted mid-session would come back to an empty log.
            List<List<ChatRelay.Segment>> backlog = ChatRelay.history();
            for (List<ChatRelay.Segment> message : backlog) {
                HUD_SERVER.sendChatTo(client, message);
            }

            // The container state is only sent on change, so a client that
            // connects while the player stands still would otherwise see an
            // empty inventory until they next moved an item.
            ContainerRelay.ScreenHandlerState container = ContainerRelay.current();
            if (container != null) {
                HUD_SERVER.sendContainerTo(client, container);
            }

            System.out.println("[ThorHud] Sent HUD asset bundle, key bindings and "
                    + backlog.size() + " chat messages to " + client.getRemoteSocketAddress());
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
