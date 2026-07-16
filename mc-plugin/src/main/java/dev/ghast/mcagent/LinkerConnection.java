package dev.ghast.mcagent;

import dev.ghast.bridge.BridgeCodec;
import dev.ghast.bridge.BridgeMessage;
import org.bukkit.plugin.Plugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages the WebSocket connection to the desktop linker, including automatic reconnection. Inbound
 * messages are decoded and passed to the supplied handler on the WebSocket thread; callers are
 * responsible for marshalling to the main server thread before touching the Bukkit API.
 */
public final class LinkerConnection {

    private final Plugin plugin;
    private final URI uri;
    private final long reconnectTicks;
    private final Consumer<BridgeMessage> handler;
    private final Runnable onConnected;
    private final Runnable onDisconnected;

    private volatile WebSocketClient client;
    private volatile boolean shuttingDown;

    public LinkerConnection(Plugin plugin,
                            String host,
                            int port,
                            String token,
                            long reconnectSeconds,
                            Consumer<BridgeMessage> handler,
                            Runnable onConnected,
                            Runnable onDisconnected) {
        this.plugin = plugin;
        String query = token == null || token.isBlank() ? "" : "/?token=" + token;
        this.uri = URI.create("ws://" + host + ":" + port + query);
        this.reconnectTicks = Math.max(20L, reconnectSeconds * 20L);
        this.handler = handler;
        this.onConnected = onConnected;
        this.onDisconnected = onDisconnected;
    }

    public URI uri() {
        return uri;
    }

    public boolean isConnected() {
        WebSocketClient c = client;
        return c != null && c.isOpen();
    }

    /** Opens the connection (non-blocking). Safe to call once at enable. */
    public synchronized void connect() {
        if (shuttingDown) {
            return;
        }
        WebSocketClient c = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                plugin.getLogger().info("Connected to linker at " + uri);
                onConnected.run();
            }

            @Override
            public void onMessage(String message) {
                try {
                    handler.accept(BridgeCodec.INSTANCE.decode(message));
                } catch (Exception e) {
                    plugin.getLogger().warning("Bad message from linker: " + e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                onDisconnected.run();
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                plugin.getLogger().log(Level.FINE, "Linker socket error", ex);
            }
        };
        c.setConnectionLostTimeout(30);
        this.client = c;
        c.connect();
    }

    /** Sends a message if connected; returns false if it could not be sent. */
    public boolean send(BridgeMessage message) {
        WebSocketClient c = client;
        if (c == null || !c.isOpen()) {
            return false;
        }
        try {
            c.send(BridgeCodec.INSTANCE.encode(message));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send to linker: " + e.getMessage());
            return false;
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!shuttingDown && !isConnected()) {
                connect();
            }
        }, reconnectTicks);
    }

    public void shutdown() {
        shuttingDown = true;
        WebSocketClient c = client;
        if (c != null) {
            c.close();
        }
    }
}
