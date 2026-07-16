package dev.ghast.linker;

import dev.ghast.bridge.BridgeCodec;
import dev.ghast.bridge.BridgeMessage;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WebSocket endpoint the Minecraft plugin connects to. Decodes inbound {@link BridgeMessage}s and
 * forwards them to a handler, and broadcasts outbound messages to every connected plugin.
 *
 * <p>If an {@code authToken} is configured, connections must supply it as a {@code ?token=} query
 * parameter or they are rejected at open time.
 */
public final class BridgeServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(BridgeServer.class);

    private final String authToken;
    private final Set<WebSocket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Consumer<BridgeMessage> handler = m -> {};
    private Runnable onConnectionsChanged = () -> {};

    public BridgeServer(String host, int port, String authToken) {
        super(new InetSocketAddress(host, port));
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken;
        setReuseAddr(true);
        // Daemon threads so the JVM can exit cleanly on shutdown (and tests don't hang on teardown).
        setDaemon(true);
    }

    public void setHandler(Consumer<BridgeMessage> handler) {
        this.handler = handler;
    }

    public void setOnConnectionsChanged(Runnable callback) {
        this.onConnectionsChanged = callback;
    }

    public int connectionCount() {
        return connections.size();
    }

    /** Sends a message to every connected plugin. */
    public void broadcast(BridgeMessage message) {
        String json = BridgeCodec.INSTANCE.encode(message);
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!authorized(handshake)) {
            log.warn("Rejected connection from {} (bad or missing token)", conn.getRemoteSocketAddress());
            conn.close(1008, "unauthorized");
            return;
        }
        connections.add(conn);
        onConnectionsChanged.run();
        log.info("Plugin connected from {} ({} total)", conn.getRemoteSocketAddress(), connections.size());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (connections.remove(conn)) {
            onConnectionsChanged.run();
            log.info("Plugin disconnected ({}) {} remaining", reason, connections.size());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            handler.accept(BridgeCodec.INSTANCE.decode(message));
        } catch (Exception e) {
            log.warn("Failed to handle plugin message: {}", e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warn("WebSocket error: {}", ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("Bridge listening on {}", getAddress());
    }

    private boolean authorized(ClientHandshake handshake) {
        if (authToken == null) {
            return true;
        }
        String resource = handshake.getResourceDescriptor();
        if (resource == null) {
            return false;
        }
        int q = resource.indexOf("token=");
        if (q < 0) {
            return false;
        }
        String value = resource.substring(q + "token=".length());
        int amp = value.indexOf('&');
        if (amp >= 0) {
            value = value.substring(0, amp);
        }
        return authToken.equals(value);
    }
}
