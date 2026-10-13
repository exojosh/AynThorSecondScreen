package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Main client lifecycle coordinator and event handler for Forge 1.20.1.
 */
public class ThorHudForgeClient {

    private static final String ICON_REQUEST_PREFIX = "ICON:";
    private static final String ASSET_REQUEST = "ASSETS";
    private static final String BINDING_REQUEST = "BINDINGS";
    private static final String HUD_VISIBILITY_PREFIX = "HUD:";
    private static final String CHAT_PREFIX = "CHAT:";
    private static final int MAX_ICON_RENDERS_PER_TICK = 2;

    private static boolean hadPlayer = false;
    private static boolean warnedAboutBindFailure = false;
    private static HudState lastHudState = null;

    public static final HudStateServer HUD_SERVER = new HudStateServer(48291);
    private static final Queue<String> iconRequestQueue = new ArrayDeque<>();
    private static final Set<String> inFlightIcons = new HashSet<>();

    public static void init() {
        ChatRelayForge.setServer(HUD_SERVER);
        HUD_SERVER.start();
        System.out.println("[ThorHud] Initialized AYN Thor Second Screen Forge mod");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        CommandDispatcherForge.tick();

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) {
            onLeftWorld();
            return;
        }
        hadPlayer = true;
        warnAboutBindFailureOnce(client);

        HudState state = new HudState(
                player.getHealth(),
                player.getMaxHealth(),
                player.getAbsorptionAmount(),
                player.getArmorValue(),
                player.getFoodData().getFoodLevel(),
                player.experienceLevel,
                player.experienceProgress,
                player.getInventory().selected,
                player.getAirSupply(),
                player.getMaxAirSupply(),
                HudState.gameModeOf(client),
                HudState.heartTypeOf(player),
                HudState.isHardcore(player),
                HudState.hotbarFromInventory(player),
                HudState.offhandFrom(player)
        );

        broadcastHudStateIfChanged(state);

        String incoming;
        while ((incoming = HUD_SERVER.pollCommand()) != null) {
            if (incoming.startsWith(ICON_REQUEST_PREFIX)) {
                queueIconRequest(incoming.substring(ICON_REQUEST_PREFIX.length()));
            } else if (incoming.equals(ASSET_REQUEST)) {
                // Asset bundle request (for clients that ask)
            } else if (incoming.equals(BINDING_REQUEST)) {
                HUD_SERVER.broadcastBindings(KeyBindingCatalogForge.snapshot());
            } else if (incoming.startsWith(HUD_VISIBILITY_PREFIX)) {
                GameHudVisibilityForge.setShownInGame(
                        incoming.substring(HUD_VISIBILITY_PREFIX.length()));
            } else if (incoming.startsWith(CHAT_PREFIX)) {
                ChatRelayForge.send(client, incoming.substring(CHAT_PREFIX.length()));
            } else if (incoming.startsWith(ContainerRelayForge.CLICK_PREFIX)) {
                ContainerRelayForge.click(client, incoming.substring(ContainerRelayForge.CLICK_PREFIX.length()));
            } else {
                CommandDispatcherForge.dispatch(incoming);
            }
        }

        pushBundleToNewClients();
        drainIconQueue();

        if (HUD_SERVER.hasClients()) {
            ContainerRelayForge.broadcastIfChanged(client, HUD_SERVER);
        }
    }

    private static void broadcastHudStateIfChanged(HudState state) {
        if (state.equals(lastHudState)) return;
        lastHudState = state;
        HUD_SERVER.broadcast(state);
    }

    private static void warnAboutBindFailureOnce(Minecraft client) {
        String failure = HUD_SERVER.bindFailure();
        if (failure == null) {
            warnedAboutBindFailure = false;
            return;
        }
        if (warnedAboutBindFailure) return;
        warnedAboutBindFailure = true;

        if (client.gui != null && client.gui.getChat() != null) {
            client.gui.getChat().addMessage(Component.literal(
                    "[Thor HUD] Port 48291 is in use. Check for background processes or leftover adb forward."));
        }
    }

    private static void onLeftWorld() {
        if (!hadPlayer) return;
        hadPlayer = false;

        ChatRelayForge.clearHistory();
        lastHudState = null;
        ContainerRelayForge.invalidate();

        HUD_SERVER.broadcastNoPlayer();
        System.out.println("[ThorHud] Player left world -- notified second screen");
    }

    private static void queueIconRequest(String itemId) {
        if (!inFlightIcons.add(itemId)) return;
        iconRequestQueue.add(itemId);
    }

    private static void drainIconQueue() {
        int budget = MAX_ICON_RENDERS_PER_TICK;
        String itemId;
        while (budget > 0 && (itemId = iconRequestQueue.poll()) != null) {
            var base64 = ItemIconResolverForge.resolveBase64Png(itemId);
            if (base64.isPresent()) {
                HUD_SERVER.broadcastIcon(itemId, base64.get());
            } else {
                HUD_SERVER.broadcastIconFailure(itemId);
            }
            inFlightIcons.remove(itemId);
            budget--;
        }
    }

    private static void pushBundleToNewClients() {
        Socket client;
        while ((client = HUD_SERVER.pollNewClient()) != null) {
            HUD_SERVER.sendBindingsTo(client, KeyBindingCatalogForge.snapshot());
            for (var msg : ChatRelayForge.history()) {
                HUD_SERVER.sendChatTo(client, msg);
            }
            if (lastHudState != null) {
                HUD_SERVER.broadcast(lastHudState);
            }
            var container = ContainerRelayForge.current();
            if (container != null) {
                HUD_SERVER.sendContainerTo(client, container);
            }
        }
    }
}
