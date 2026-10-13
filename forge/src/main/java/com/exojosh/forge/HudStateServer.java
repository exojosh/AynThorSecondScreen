package com.exojosh.forge;

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
 * whatever lines it receives onto a shared queue, drained via pollCommand()
 * from the client tick thread.
 */
public class HudStateServer {

    private static final long BIND_RETRY_MS = 5000;
    private static final String MOD_ID = "aynthor_secondscreen";
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
        Thread acceptThread = new Thread(this::acceptLoop, "ThorHud-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public String bindFailure() {
        return bindFailure;
    }

    public Socket pollNewClient() {
        return newClients.poll();
    }

    public String pollCommand() {
        return incomingCommands.poll();
    }

    public void broadcast(HudState state) {
        broadcastRaw(gson.toJson(state));
    }

    public void broadcastNoPlayer() {
        broadcastRaw("{\"type\":\"noplayer\"}");
    }

    public void broadcastIcon(String itemId, String base64Png) {
        record IconResponse(String type, String itemId, String data) {}
        broadcastRaw(gson.toJson(new IconResponse("icon", itemId, base64Png)));
    }

    public void broadcastIconFailure(String itemId) {
        record IconFailure(String type, String itemId) {}
        broadcastRaw(gson.toJson(new IconFailure("icon", itemId)));
    }

    public void broadcastAsset(String assetId, String base64Png) {
        record AssetResponse(String type, String assetId, String data) {}
        broadcastRaw(gson.toJson(new AssetResponse("asset", assetId, base64Png)));
    }

    public void sendAssetTo(Socket client, String assetId, String base64Png) {
        record AssetResponse(String type, String assetId, String data) {}
        sendTo(client, gson.toJson(new AssetResponse("asset", assetId, base64Png)));
    }

    public void broadcastBindings(List<KeyBindingCatalogForge.Entry> bindings) {
        record BindingsResponse(String type, List<KeyBindingCatalogForge.Entry> bindings) {}
        broadcastRaw(gson.toJson(new BindingsResponse("bindings", bindings)));
    }

    public void sendBindingsTo(Socket client, List<KeyBindingCatalogForge.Entry> bindings) {
        record BindingsResponse(String type, List<KeyBindingCatalogForge.Entry> bindings) {}
        sendTo(client, gson.toJson(new BindingsResponse("bindings", bindings)));
    }

    public void broadcastChat(List<ChatRelayForge.Segment> segments) {
        record ChatMessage(String type, List<ChatRelayForge.Segment> segments) {}
        broadcastRaw(gson.toJson(new ChatMessage("chat", segments)));
    }

    public void sendChatTo(Socket client, List<ChatRelayForge.Segment> segments) {
        record ChatMessage(String type, List<ChatRelayForge.Segment> segments) {}
        sendTo(client, gson.toJson(new ChatMessage("chat", segments)));
    }

    public void broadcastContainer(ContainerRelayForge.ScreenHandlerState state) {
        broadcastRaw(gson.toJson(state));
    }

    public void sendContainerTo(Socket client, ContainerRelayForge.ScreenHandlerState state) {
        sendTo(client, gson.toJson(state));
    }

    public void broadcastMap(int originX, int originZ, int scale, String base64Rgba) {
        record MapResponse(String type, int originX, int originZ, int scale, String data) {}
        broadcastRaw(gson.toJson(new MapResponse("map", originX, originZ, scale, base64Rgba)));
    }

    private void acceptLoop() {
        while (true) {
            try {
                serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
                bindFailure = null;
                System.out.println("[ThorHud] Listening on loopback port " + port);

                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    clients.add(socket);
                    sendHello(socket);
                    newClients.add(socket);

                    Thread reader = new Thread(() -> clientReadLoop(socket), "ThorHud-Reader-" + socket.getPort());
                    reader.setDaemon(true);
                    reader.start();
                }
            } catch (IOException e) {
                bindFailure = e.getMessage();
                System.out.println("[ThorHud] Bind/accept failed on port " + port + ": " + e.getMessage()
                        + " -- retrying in " + (BIND_RETRY_MS / 1000) + "s");
                closeQuietly(serverSocket);
                serverSocket = null;
                try {
                    Thread.sleep(BIND_RETRY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sendHello(Socket socket) {
        record HelloMessage(String type, String mod, int protocol) {}
        sendTo(socket, gson.toJson(new HelloMessage("hello", MOD_ID, PROTOCOL_VERSION)));
    }

    private void clientReadLoop(Socket socket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    incomingCommands.add(trimmed);
                }
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(socket);
            closeQuietly(socket);
        }
    }

    private void broadcastRaw(String json) {
        byte[] payload = (json + "\n").getBytes(StandardCharsets.UTF_8);
        for (Socket client : clients) {
            try {
                OutputStream out = client.getOutputStream();
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                clients.remove(client);
                closeQuietly(client);
            }
        }
    }

    private void sendTo(Socket client, String json) {
        byte[] payload = (json + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            OutputStream out = client.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            clients.remove(client);
            closeQuietly(client);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
