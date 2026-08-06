package com.exojosh.client;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bidirectional newline-delimited-JSON server, bound to loopback only.
 *
 * Outgoing: broadcast() pushes a HudState line to every connected client.
 * Incoming: each connected client gets its own reader thread that pushes
 * whatever lines it receives (expected to be short command codes like "R")
 * onto a shared queue, drained via pollCommand() from the client tick thread.
 *
 * Still deliberately simple: no handshake, no auth (loopback-only), no
 * framing beyond newlines. Good enough for one companion app talking to
 * one game instance on the same device.
 */
public class HudStateServer {

    /** How long to wait before trying the bind again. Long enough not to spam
     *  the log, short enough that clearing a conflict feels immediate. */
    private static final long BIND_RETRY_MS = 5000;

    /** Identifies what answered on this port, so the app can tell the mod apart
     *  from anything else that happens to accept a connection there. */
    private static final String MOD_ID = "aynthor_secondscreen";

    /** Bumped only when a change would break an older app build. The app uses
     *  it to say "update the other half" rather than misbehaving. */
    private static final int PROTOCOL_VERSION = 1;

    private final Gson gson = new Gson();
    private final int port;
    private volatile String bindFailure;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> incomingCommands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Socket> newClients = new ConcurrentLinkedQueue<>();
    private ServerSocket serverSocket;

    public HudStateServer(int port) {
        this.port = port;
    }

    public void start() {
        Thread acceptThread = new Thread(this::acceptLoop, "thorhud-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Keeps trying to own the port, rather than giving up for the session.
     *
     * This used to catch the {@link java.net.BindException} and <em>return</em>,
     * which killed the accept thread for good: the second screen was dead until
     * the game was restarted, with nothing said anywhere the player looks. It
     * happened for real — a leftover {@code adb reverse tcp:48291 tcp:48291}
     * from a capture run left adbd owning the port, so the mod never bound it
     * and the app connected to <em>adbd</em>, which accepted and immediately
     * dropped every connection. On the second screen that reads as "Not
     * connected" alternating with "Waiting for map data…", which looks exactly
     * like a broken app update and says nothing about a port conflict.
     *
     * Retrying means clearing the conflict is enough on its own — no restart.
     */
    private void acceptLoop() {
        while (true) {
            try (ServerSocket socket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
                serverSocket = socket;
                bindFailure = null;
                System.out.println("[ThorHud] Listening on 127.0.0.1:" + port);

                while (!socket.isClosed()) {
                    Socket client = socket.accept();
                    System.out.println("[ThorHud] Client connected: " + client.getRemoteSocketAddress());

                    // Sent from this thread, before anything else, so it lands
                    // even if the client thread is mid-frame. It's the one
                    // message that says *what* answered on this port.
                    writeLine(client, gson.toJson(new HelloResponse("hello", MOD_ID, PROTOCOL_VERSION)));

                    clients.add(client);
                    // Picked up on the next client tick, which is where the HUD
                    // asset bundle gets pushed -- resolving textures needs the
                    // resource manager, so it has to happen on the client
                    // thread, not here on the accept thread.
                    newClients.add(client);

                    Thread readerThread = new Thread(() -> readLoop(client), "thorhud-reader");
                    readerThread.setDaemon(true);
                    readerThread.start();
                }
            } catch (IOException e) {
                bindFailure = e.getMessage() == null ? e.toString() : e.getMessage();
                // System.err, not System.out: this is the one failure whose
                // audience is on the device that cannot be told. The tick loop
                // also surfaces it in the player's own chat, which is the only
                // screen guaranteed to be working when this happens.
                System.err.println("[ThorHud] Could not listen on 127.0.0.1:" + port
                        + " (" + bindFailure + "); retrying in " + (BIND_RETRY_MS / 1000) + "s. "
                        + "A leftover 'adb reverse tcp:" + port + "' is the usual cause.");
            }

            try {
                Thread.sleep(BIND_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The current bind failure, or null if the port is ours.
     *
     * Polled by the tick loop so it can say so in the player's own chat — see
     * the note in {@link #acceptLoop}.
     */
    public String bindFailure() {
        return bindFailure;
    }

    private void readLoop(Socket client) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    incomingCommands.add(trimmed);
                }
            }
        } catch (IOException e) {
            // Client disconnected -- fall through to cleanup below.
        } finally {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Returns and removes the oldest queued command, or null if none waiting. */
    public String pollCommand() {
        return incomingCommands.poll();
    }

    /**
     * Returns a client that has connected since this was last called, or null.
     * Lets the tick loop push the HUD asset bundle to just the newcomer
     * instead of re-broadcasting it to everyone already connected.
     */
    public Socket pollNewClient() {
        return newClients.poll();
    }

    public void broadcast(HudState state) {
        send(gson.toJson(state));
    }

    public void broadcastIcon(String itemId, String base64Png) {
        send(gson.toJson(new IconResponse("icon", itemId, base64Png)));
    }

    /**
     * Tells the app "we tried, there's no icon for this one" instead of just
     * never replying. Without an explicit answer the app can't distinguish a
     * slow render from a dropped request, so it either retries forever or
     * (as it used to) gives up permanently on the first miss.
     */
    public void broadcastIconFailure(String itemId) {
        send(gson.toJson(new IconResponse("icon", itemId, null)));
    }

    /** Broadcasts one HUD texture, keyed by HudAssetCatalog's short name. */
    public void broadcastAsset(String assetId, String base64Png) {
        send(gson.toJson(new AssetResponse("asset", assetId, base64Png)));
    }

    /** Sends one HUD texture to a single client -- used for the bundle a
     *  newly-connected app gets, which nobody else needs re-sent. */
    public void sendAssetTo(Socket client, String assetId, String base64Png) {
        writeLine(client, gson.toJson(new AssetResponse("asset", assetId, base64Png)));
    }

    /**
     * Ships the full key-binding list.
     *
     * Sent whole, never as a delta, for the same reason the HUD-visibility set
     * is: the app builds its picker straight from this, and a partial list
     * silently becomes "that action doesn't exist" rather than an error anyone
     * would notice.
     */
    public void broadcastBindings(List<KeyBindingCatalog.Entry> bindings) {
        send(gson.toJson(new BindingsResponse("bindings", bindings)));
    }

    /** The same list to a single client -- what a newly-connected app gets,
     *  alongside the texture bundle, without anyone else being re-sent it. */
    public void sendBindingsTo(Socket client, List<KeyBindingCatalog.Entry> bindings) {
        writeLine(client, gson.toJson(new BindingsResponse("bindings", bindings)));
    }

    /**
     * Broadcasts one chat message, as coloured runs.
     *
     * See {@link ChatRelay} for why it's runs rather than a plain string, and
     * why only colour survives the flattening.
     */
    public void broadcastChat(List<ChatRelay.Segment> segments) {
        send(gson.toJson(new ChatResponse("chat", segments)));
    }

    /** One chat message to a single client -- used for the backlog a newly
     *  connected app gets, which nobody else needs re-sent. */
    public void sendChatTo(Socket client, List<ChatRelay.Segment> segments) {
        writeLine(client, gson.toJson(new ChatResponse("chat", segments)));
    }

    /**
     * Tells the app there is no player — main menu, world unloading, kicked.
     *
     * A message rather than simply going quiet, because silence is
     * indistinguishable from a stalled game: the app would keep displaying the
     * last snapshot indefinitely with no way to know it was stale.
     */
    public void broadcastNoPlayer() {
        send(gson.toJson(new NoPlayerResponse("noplayer")));
    }

    /**
     * Broadcasts the open screen handler's contents.
     *
     * The record already carries its own {@code type} field, so unlike every
     * other message here it needs no wrapper — see {@link ContainerRelay}.
     */
    public void broadcastContainer(ContainerRelay.ScreenHandlerState state) {
        send(gson.toJson(state));
    }

    /** The same, to a single client -- what a newly-connected app gets, so it
     *  doesn't sit empty until the player next moves an item. */
    public void sendContainerTo(Socket client, ContainerRelay.ScreenHandlerState state) {
        writeLine(client, gson.toJson(state));
    }

    /** Broadcasts one rendered map tile. */
    public void broadcastMap(MapRenderer.Tile tile) {
        send(gson.toJson(new MapResponse(
                "map",
                tile.base64Png(),
                tile.originX(),
                tile.originZ(),
                tile.playerX(),
                tile.playerZ(),
                tile.yaw(),
                tile.size())));
    }

    /**
     * Whether anyone is listening.
     *
     * Lets the tick loop skip work nobody will see -- rendering a map tile
     * every few ticks into a socket with no client on it is pure battery
     * drain on a handheld.
     */
    public boolean hasClients() {
        return !clients.isEmpty();
    }

    private void send(String json) {
        if (clients.isEmpty()) return;
        for (Socket client : clients) {
            writeLine(client, json);
        }
    }

    private void writeLine(Socket client, String json) {
        byte[] payload = (json + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            // Synchronized because the asset bundle is written to one client
            // in a loop while broadcast() may be writing a state line to the
            // same socket; interleaved writes would corrupt both.
            synchronized (client) {
                OutputStream out = client.getOutputStream();
                out.write(payload);
                out.flush();
            }
        } catch (IOException e) {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** type is always "icon" -- lets the companion app tell this apart from a HudState line. */
    public record IconResponse(String type, String itemId, String data) {}

    /**
     * type is always "bindings". Every key binding the game has, display text
     * already translated -- see {@link KeyBindingCatalog} for why that
     * resolution happens here and not in the app.
     */
    public record BindingsResponse(String type, List<KeyBindingCatalog.Entry> bindings) {}

    /**
     * type is always "chat". One message, as a list of coloured runs -- see
     * {@link ChatRelay} for why the styling is reduced to colour alone.
     */
    public record ChatResponse(String type, List<ChatRelay.Segment> segments) {}

    /** type is always "noplayer". Carries nothing -- it's a latch, and the app
     *  holds that state until a real snapshot arrives. */
    public record NoPlayerResponse(String type) {}

    /**
     * type is always "hello". The first line on every connection.
     *
     * Exists so the app can tell "connected to the mod" from "connected to
     * *something* on 48291" -- a leftover `adb reverse` puts adbd on this port,
     * and adbd accepts and instantly drops every connection, which the app
     * previously reported as an ordinary flapping connection.
     */
    public record HelloResponse(String type, String mod, int protocol) {}

    /** type is always "asset". data is null when the resource pack stack
     *  doesn't provide that texture, so the app can fall back immediately
     *  rather than waiting for something that isn't coming. */
    public record AssetResponse(String type, String assetId, String data) {}

    /**
     * type is always "map". A top-down PNG tile plus where it sits in the world.
     *
     * originX/originZ are the block coordinates of the tile's top-left pixel,
     * sent explicitly so the app can place the player marker without
     * reimplementing MapRenderer's centring. yaw is degrees, vanilla's
     * convention (0 = south, increasing clockwise).
     */
    public record MapResponse(
            String type,
            String data,
            int originX,
            int originZ,
            double playerX,
            double playerZ,
            float yaw,
            int size
    ) {}
}
