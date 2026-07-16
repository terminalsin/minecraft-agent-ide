package dev.ghast.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ghast.acp.json.Json;
import dev.ghast.acp.model.ClientCapabilities;
import dev.ghast.acp.model.InitializeResult;
import dev.ghast.acp.model.PermissionOption;
import dev.ghast.acp.model.PermissionRequest;
import dev.ghast.acp.model.SessionUpdate;
import dev.ghast.acp.rpc.JsonRpcPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A live connection to an ACP agent running as a child process, communicating over its stdio via
 * JSON-RPC. Provides typed helpers for the client-initiated part of the protocol and routes the
 * agent's callbacks to a {@link ClientSideHandler}.
 */
public final class AgentConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentConnection.class);

    private final Process process;
    private final JsonRpcPeer peer;
    private final ClientSideHandler handler;

    private AgentConnection(Process process, JsonRpcPeer peer, ClientSideHandler handler) {
        this.process = process;
        this.peer = peer;
        this.handler = handler;
    }

    /**
     * Spawns the agent command and establishes the JSON-RPC connection over its stdio.
     * The agent's stderr is drained to the log.
     */
    public static AgentConnection spawn(List<String> command,
                                        File workingDir,
                                        Map<String, String> environment,
                                        ClientSideHandler handler) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir);
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        log.info("Spawned ACP agent: {} (pid {})", String.join(" ", command), process.pid());

        JsonRpcPeer peer = new JsonRpcPeer(process.getInputStream(), process.getOutputStream());
        AgentConnection connection = new AgentConnection(process, peer, handler);
        connection.registerHandlers();
        connection.drainStderr();
        peer.start();
        return connection;
    }

    private void registerHandlers() {
        peer.onNotification(AcpConstants.METHOD_SESSION_UPDATE, params -> {
            String sessionId = Json.text(params, "sessionId");
            SessionUpdate update = SessionUpdate.parse(params == null ? null : params.get("update"));
            handler.onSessionUpdate(sessionId, update);
        });

        peer.onRequest(AcpConstants.METHOD_REQUEST_PERMISSION, params -> {
            PermissionRequest request = new PermissionRequest(
                    Json.text(params, "sessionId"),
                    params.get("toolCall"),
                    parseOptions(params.get("options")));
            return handler.onRequestPermission(request).thenApply(AgentConnection::permissionResult);
        });

        peer.onRequest(AcpConstants.METHOD_FS_READ_TEXT_FILE, params -> {
            String content = handler.onReadTextFile(
                    Json.text(params, "sessionId"),
                    Json.text(params, "path"),
                    intOrNull(params, "line"),
                    intOrNull(params, "limit"));
            ObjectNode result = Json.object();
            result.put("content", content);
            return CompletableFuture.completedFuture(result);
        });

        peer.onRequest(AcpConstants.METHOD_FS_WRITE_TEXT_FILE, params -> {
            handler.onWriteTextFile(
                    Json.text(params, "sessionId"),
                    Json.text(params, "path"),
                    Json.text(params, "content"));
            return CompletableFuture.completedFuture(null);
        });
    }

    private void drainStderr() {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[agent stderr] {}", line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "acp-agent-stderr");
        t.setDaemon(true);
        t.start();
    }

    /** Performs the {@code initialize} handshake, advertising filesystem client capabilities. */
    public CompletableFuture<InitializeResult> initialize() {
        ObjectNode params = Json.object();
        params.put("protocolVersion", AcpConstants.PROTOCOL_VERSION);
        params.set("clientCapabilities", Json.toTree(ClientCapabilities.filesystemOnly()));
        return peer.request(AcpConstants.METHOD_INITIALIZE, params).thenApply(InitializeResult::parse);
    }

    /** Authenticates using the given auth method id (only needed when the agent requires it). */
    public CompletableFuture<Void> authenticate(String methodId) {
        ObjectNode params = Json.object();
        params.put("methodId", methodId);
        return peer.request(AcpConstants.METHOD_AUTHENTICATE, params).thenApply(node -> null);
    }

    /** Creates a new session rooted at {@code cwd}. Returns the agent-assigned session id. */
    public CompletableFuture<String> newSession(String cwd) {
        ObjectNode params = Json.object();
        params.put("cwd", cwd);
        params.set("mcpServers", Json.MAPPER.createArrayNode());
        return peer.request(AcpConstants.METHOD_SESSION_NEW, params)
                .thenApply(result -> Json.text(result, "sessionId"));
    }

    /** Sends a plain-text prompt to the session. Completes with the turn's {@code stopReason}. */
    public CompletableFuture<String> prompt(String sessionId, String text) {
        ObjectNode params = Json.object();
        params.put("sessionId", sessionId);
        ArrayNode blocks = params.putArray("prompt");
        ObjectNode block = blocks.addObject();
        block.put("type", "text");
        block.put("text", text);
        return peer.request(AcpConstants.METHOD_SESSION_PROMPT, params)
                .thenApply(result -> result == null ? AcpConstants.STOP_END_TURN
                        : result.path("stopReason").asText(AcpConstants.STOP_END_TURN));
    }

    /** Requests cancellation of the current turn for the session (a notification, no reply). */
    public void cancel(String sessionId) {
        ObjectNode params = Json.object();
        params.put("sessionId", sessionId);
        peer.notify(AcpConstants.METHOD_SESSION_CANCEL, params);
    }

    public void onClose(Runnable callback) {
        peer.onClose(callback);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        peer.close();
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static ObjectNode permissionResult(String optionId) {
        ObjectNode result = Json.object();
        ObjectNode outcome = result.putObject("outcome");
        if (optionId == null) {
            outcome.put("outcome", "cancelled");
        } else {
            outcome.put("outcome", "selected");
            outcome.put("optionId", optionId);
        }
        return result;
    }

    private static List<PermissionOption> parseOptions(JsonNode options) {
        List<PermissionOption> out = new ArrayList<>();
        if (options != null && options.isArray()) {
            for (JsonNode option : options) {
                out.add(new PermissionOption(
                        Json.text(option, "optionId"),
                        Json.text(option, "name"),
                        Json.text(option, "kind")));
            }
        }
        return out;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asInt();
    }
}
