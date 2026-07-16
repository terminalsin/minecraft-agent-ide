package dev.ghast.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ghast.acp.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bidirectional JSON-RPC 2.0 peer speaking newline-delimited JSON (NDJSON) over a pair of
 * streams — the wire format ACP uses over an agent subprocess's stdio.
 *
 * <p>Both sides may issue requests and notifications at any time, so this class handles all four
 * message shapes: outbound requests (matched to a {@link CompletableFuture} by id), inbound
 * requests (dispatched to a {@link RequestHandler}), outbound notifications, and inbound
 * notifications (dispatched to a {@link NotificationHandler}).
 */
public final class JsonRpcPeer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcPeer.class);

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private final Map<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile boolean closed = false;
    private Thread readerThread;
    private Runnable onClose = () -> {};

    public JsonRpcPeer(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    /** Registers a handler invoked when the peer calls {@code method} as a request. */
    public JsonRpcPeer onRequest(String method, RequestHandler handler) {
        requestHandlers.put(method, handler);
        return this;
    }

    /** Registers a handler invoked when the peer sends {@code method} as a notification. */
    public JsonRpcPeer onNotification(String method, NotificationHandler handler) {
        notificationHandlers.put(method, handler);
        return this;
    }

    /** Sets a callback fired once when the connection ends (EOF or {@link #close()}). */
    public void onClose(Runnable callback) {
        this.onClose = callback;
    }

    /** Starts the background reader thread. Must be called before sending requests. */
    public void start() {
        readerThread = new Thread(this::readLoop, "acp-rpc-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Sends a request and returns a future completed with its {@code result} node (may be null). */
    public CompletableFuture<JsonNode> request(String method, Object params) {
        long id = nextId.getAndIncrement();
        ObjectNode message = Json.object();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", Json.toTree(params));
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeMessage(message);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Sends a fire-and-forget notification. */
    public void notify(String method, Object params) {
        ObjectNode message = Json.object();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", Json.toTree(params));
        }
        try {
            writeMessage(message);
        } catch (IOException e) {
            log.warn("Failed to send notification {}: {}", method, e.getMessage());
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    dispatch(Json.MAPPER.readTree(line));
                } catch (Exception e) {
                    log.warn("Failed to process incoming message: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("Reader stream ended: {}", e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode idNode = message.get("id");
        boolean hasMethod = message.hasNonNull("method");

        if (hasMethod && idNode != null && !idNode.isNull()) {
            handleInboundRequest(idNode, message.get("method").asText(), message.get("params"));
        } else if (hasMethod) {
            handleInboundNotification(message.get("method").asText(), message.get("params"));
        } else if (idNode != null && !idNode.isNull()) {
            handleResponse(idNode.asLong(), message);
        } else {
            log.warn("Ignoring malformed JSON-RPC message: {}", message);
        }
    }

    private void handleResponse(long id, JsonNode message) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            log.warn("Received response for unknown id {}", id);
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            int code = error.path("code").asInt(0);
            String msg = error.path("message").asText("unknown error");
            future.completeExceptionally(new JsonRpcException(code, msg, error.get("data")));
        } else {
            future.complete(message.get("result"));
        }
    }

    private void handleInboundRequest(JsonNode id, String method, JsonNode params) {
        RequestHandler handler = requestHandlers.get(method);
        if (handler == null) {
            sendError(id, -32601, "Method not found: " + method);
            return;
        }
        CompletableFuture<Object> result;
        try {
            result = handler.handle(params);
        } catch (Exception e) {
            sendErrorFor(id, e);
            return;
        }
        result.whenComplete((value, error) -> {
            if (error != null) {
                sendErrorFor(id, error);
            } else {
                sendResult(id, value);
            }
        });
    }

    private void handleInboundNotification(String method, JsonNode params) {
        NotificationHandler handler = notificationHandlers.get(method);
        if (handler == null) {
            log.debug("No handler for notification {}", method);
            return;
        }
        try {
            handler.handle(params);
        } catch (Exception e) {
            log.warn("Notification handler for {} threw: {}", method, e.getMessage());
        }
    }

    private void sendResult(JsonNode id, Object value) {
        ObjectNode message = Json.object();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", value == null ? Json.MAPPER.nullNode() : Json.toTree(value));
        writeQuietly(message);
    }

    private void sendErrorFor(JsonNode id, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof JsonRpcException rpc) {
            sendError(id, rpc.code(), rpc.getMessage());
        } else {
            sendError(id, -32603, "Internal error: " + cause.getMessage());
        }
    }

    private void sendError(JsonNode id, int code, String messageText) {
        ObjectNode message = Json.object();
        message.put("jsonrpc", "2.0");
        message.set("id", id == null ? Json.MAPPER.nullNode() : id);
        ObjectNode error = message.putObject("error");
        error.put("code", code);
        error.put("message", messageText);
        writeQuietly(message);
    }

    private void writeMessage(ObjectNode message) throws IOException {
        String json = Json.MAPPER.writeValueAsString(message);
        synchronized (writeLock) {
            writer.write(json);
            writer.write('\n');
            writer.flush();
        }
    }

    private void writeQuietly(ObjectNode message) {
        try {
            writeMessage(message);
        } catch (IOException e) {
            log.warn("Failed to write message: {}", e.getMessage());
        }
    }

    private void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        JsonRpcException dead = new JsonRpcException(-32000, "Connection closed", null);
        pending.values().forEach(f -> f.completeExceptionally(dead));
        pending.clear();
        try {
            onClose.run();
        } catch (Exception e) {
            log.debug("onClose callback threw: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            reader.close();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            synchronized (writeLock) {
                writer.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        shutdown();
    }
}
