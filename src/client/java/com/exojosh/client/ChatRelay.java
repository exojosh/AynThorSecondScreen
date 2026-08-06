package com.exojosh.client;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.StringHelper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chat in both directions.
 *
 * <h2>Receiving</h2>
 * Hooks Fabric's {@code ClientReceiveMessageEvents} rather than mixing into
 * {@code ChatHud.addMessage}: the events fire at exactly the point vanilla
 * decides a message is going to be shown, so anything another mod suppresses
 * never reaches the second screen either.
 *
 * Both event families are relayed, because "chat" on screen is both of them:
 * CHAT is player-sent messages, GAME is everything the server says (join and
 * leave notices, death messages, {@code /say}, command output). GAME with
 * {@code overlay == true} is the action bar, not chat, and is skipped -- vanilla
 * doesn't put those in the chat log.
 *
 * The CHAT event's message arrives **already decorated** (Fabric passes the
 * {@code decorated} local from {@code MessageHandler.showMessageToPlayer}), so
 * applying {@code params.applyChatDecoration} here would double up the
 * {@code <name>} prefix.
 *
 * <h2>Wire format</h2>
 * A message goes out as a list of coloured runs, not a plain string:
 * {@code {"type":"chat","segments":[{"text":"<Steve> ","color":16777215},…]}}.
 * Colour is the one part of Minecraft's text styling that carries meaning in a
 * chat log -- team colours, the red of a death message, the yellow of a join
 * notice -- so flattening to plain text would lose information the player
 * actually reads. Bold/italic/obfuscated are deliberately dropped: the app
 * draws from a bitmap font sheet, where those need their own draw passes, and
 * they carry far less. Same class of simplification as the static enchant
 * glint.
 *
 * A {@code color} of null means "the default", which the app renders white --
 * Gson omits the field entirely in that case.
 *
 * <h2>Sending</h2>
 * {@link #send} follows {@code ChatScreen.sendMessage}: normalise, then split
 * commands off to {@code sendChatCommand} so argument signing and the client's
 * own command dispatcher are used, exactly as typing it in game would.
 *
 * Note this deliberately does <em>not</em> go through {@link CommandDispatcher}
 * -- see the note in {@link ThorHudClient} about the screen-open guard.
 */
public final class ChatRelay {

    /**
     * How many messages are kept for a newly-connected app. Vanilla's ChatHud
     * keeps the same number (its MAX_MESSAGES), so the second screen shows the
     * same amount of history the top screen would.
     *
     * The backlog is why closing and reopening the companion app mid-session
     * doesn't lose the conversation: nothing re-sends chat on its own, so
     * without this a reconnect would come back to an empty log.
     */
    private static final int HISTORY_LIMIT = 100;

    /** Guarded by itself -- written from the client thread (where the receive
     *  events fire) and read from it too, but a socket accept can land between
     *  the two, so the copy handed to a new client has to be atomic. */
    private static final Deque<List<Segment>> HISTORY = new ArrayDeque<>();

    private static HudStateServer server;

    /** One run of chat text sharing a colour. {@code color} is RGB, or null
     *  for "whatever the app's default is". */
    public record Segment(String text, Integer color) {
    }

    private ChatRelay() {
    }

    public static void install(HudStateServer hudServer) {
        server = hudServer;

        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> relay(message));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // The action bar is its own HUD element, not part of the chat log.
            if (overlay) return;
            relay(message);
        });
    }

    /** Every message still held, oldest first. */
    public static List<List<Segment>> history() {
        synchronized (HISTORY) {
            return new ArrayList<>(HISTORY);
        }
    }

    /**
     * Sends a line of chat as the player.
     *
     * Mirrors {@code ChatScreen}: the text is normalised the way the game's own
     * chat box normalises it (trimmed, runs of whitespace collapsed, truncated
     * to the protocol's limit), and a leading {@code /} routes it to
     * {@code sendChatCommand} rather than {@code sendChatMessage}. That split
     * matters -- commands get their arguments signed and parsed through the
     * client's dispatcher, and sending "/tp …" as a chat message would just say
     * it out loud.
     */
    public static void send(MinecraftClient client, String rawMessage) {
        if (client.player == null) return;

        String message = StringHelper.truncateChat(StringUtils.normalizeSpace(rawMessage.trim()));
        if (message.isEmpty()) return;

        if (message.startsWith("/")) {
            client.player.networkHandler.sendChatCommand(message.substring(1));
        } else {
            client.player.networkHandler.sendChatMessage(message);
        }
    }

    private static void relay(Text message) {
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

    /**
     * Flattens a Text tree into coloured runs.
     *
     * {@code visit(StyledVisitor, Style.EMPTY)} is what does the real work: it
     * walks content and siblings, resolves each node's style against its
     * parent's, and expands translatable content into the player's language.
     * Reading {@code getString()} would give the same characters with none of
     * that resolution, and walking {@code getSiblings()} by hand would mean
     * re-deriving the style inheritance.
     *
     * Adjacent runs of the same colour are merged, because a Text tree is
     * usually far more fragmented than it looks -- a translated death message
     * can arrive as a dozen same-styled nodes.
     */
    private static List<Segment> flatten(Text message) {
        List<Segment> segments = new ArrayList<>();

        StringVisitable.StyledVisitor<Object> visitor = (style, asString) -> {
            if (!asString.isEmpty()) {
                TextColor color = style.getColor();
                Integer rgb = color == null ? null : color.getRgb();

                if (!segments.isEmpty()) {
                    Segment last = segments.get(segments.size() - 1);
                    if (Objects.equals(last.color(), rgb)) {
                        segments.set(segments.size() - 1, new Segment(last.text() + asString, rgb));
                        return Optional.empty();
                    }
                }

                segments.add(new Segment(asString, rgb));
            }
            // Empty means "keep going"; a present value would terminate the visit.
            return Optional.empty();
        };

        message.visit(visitor, Style.EMPTY);
        return segments;
    }
}
