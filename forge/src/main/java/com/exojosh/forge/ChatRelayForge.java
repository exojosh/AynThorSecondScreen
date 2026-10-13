package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.StringUtil;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles incoming chat capture via Forge event and outbound chat sending for Forge 1.20.1.
 */
public final class ChatRelayForge {

    private static final int HISTORY_LIMIT = 100;
    private static final Deque<List<Segment>> HISTORY = new ArrayDeque<>();
    private static HudStateServer server;

    public record Segment(String text, Integer color) {}

    private ChatRelayForge() {}

    public static void setServer(HudStateServer hudServer) {
        server = hudServer;
    }

    @SubscribeEvent
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event instanceof ClientChatReceivedEvent.System sys && sys.isOverlay()) {
            return;
        }
        relay(event.getMessage());
    }

    public static List<List<Segment>> history() {
        synchronized (HISTORY) {
            return new ArrayList<>(HISTORY);
        }
    }

    public static void clearHistory() {
        synchronized (HISTORY) {
            HISTORY.clear();
        }
    }

    public static void send(Minecraft client, String rawMessage) {
        if (client.player == null || client.player.connection == null) return;

        String trimmed = rawMessage.trim();
        String message = StringUtil.trimChatMessage(StringUtils.normalizeSpace(trimmed));
        if (message.isEmpty()) return;

        if (message.startsWith("/")) {
            client.player.connection.sendCommand(message.substring(1));
        } else {
            client.player.connection.sendChat(message);
        }
    }

    private static void relay(Component message) {
        List<Segment> segments = flatten(message);
        if (segments.isEmpty()) return;

        synchronized (HISTORY) {
            HISTORY.addLast(segments);
            while (HISTORY.size() > HISTORY_LIMIT) {
                HISTORY.removeFirst();
            }
        }

        if (server != null) {
            server.broadcastChat(segments);
        }
    }

    private static List<Segment> flatten(Component message) {
        List<Segment> segments = new ArrayList<>();

        message.visit((style, asString) -> {
            if (!asString.isEmpty()) {
                TextColor color = style.getColor();
                Integer rgb = color == null ? null : color.getValue();

                if (!segments.isEmpty()) {
                    Segment last = segments.get(segments.size() - 1);
                    if (Objects.equals(last.color(), rgb)) {
                        segments.set(segments.size() - 1, new Segment(last.text() + asString, rgb));
                        return Optional.empty();
                    }
                }

                segments.add(new Segment(asString, rgb));
            }
            return Optional.empty();
        }, Style.EMPTY);

        return segments;
    }
}
