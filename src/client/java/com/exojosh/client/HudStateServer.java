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

    private final Gson gson = new Gson();
    private final int port;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> incomingCommands = new ConcurrentLinkedQueue<>();
    private ServerSocket serverSocket;

    public HudStateServer(int port) {
        this.port = port;
    }

    public void start() {
        Thread acceptThread = new Thread(this::acceptLoop, "thorhud-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
            System.out.println("[ThorHud] Listening on 127.0.0.1:" + port);
            while (!serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                System.out.println("[ThorHud] Client connected: " + client.getRemoteSocketAddress());
                clients.add(client);

                Thread readerThread = new Thread(() -> readLoop(client), "thorhud-reader");
                readerThread.setDaemon(true);
                readerThread.start();
            }
        } catch (IOException e) {
            System.out.println("[ThorHud] Failed to bind/accept on port " + port + ":");
            e.printStackTrace();
        }
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

    private void send(String json) {
        if (clients.isEmpty()) return;

        String line = json + "\n";
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);

        for (Socket client : clients) {
            try {
                OutputStream out = client.getOutputStream();
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                clients.remove(client);
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** type is always "icon" -- lets the companion app tell this apart from a HudState line. */
    public record IconResponse(String type, String itemId, String data) {}
}
