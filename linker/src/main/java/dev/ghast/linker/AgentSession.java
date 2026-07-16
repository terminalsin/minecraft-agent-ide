package dev.ghast.linker;

import dev.ghast.acp.AgentConnection;
import dev.ghast.acp.ClientSideHandler;
import dev.ghast.acp.model.PermissionOption;
import dev.ghast.acp.model.PermissionRequest;
import dev.ghast.acp.model.PlanEntry;
import dev.ghast.acp.model.SessionUpdate;
import dev.ghast.bridge.BridgeMessage;
import dev.ghast.linker.config.AgentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A single villager-to-agent binding. Owns one {@link AgentConnection} and implements the ACP
 * client callbacks, translating agent activity into {@link BridgeMessage}s for the Minecraft
 * plugin and answering the agent's permission and filesystem requests.
 */
public final class AgentSession implements ClientSideHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    private final String villagerId;
    private final AgentProfile profile;
    private final Path cwd;
    private final Consumer<BridgeMessage> toPlugin;
    private final Map<String, CompletableFuture<String>> pendingPermissions = new ConcurrentHashMap<>();

    private volatile AgentConnection connection;
    private volatile String sessionId;

    public AgentSession(String villagerId, AgentProfile profile, Path cwd, Consumer<BridgeMessage> toPlugin) {
        this.villagerId = villagerId;
        this.profile = profile;
        this.cwd = cwd;
        this.toPlugin = toPlugin;
    }

    void attach(AgentConnection connection) {
        this.connection = connection;
    }

    void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String villagerId() {
        return villagerId;
    }

    public String sessionId() {
        return sessionId;
    }

    public AgentProfile profile() {
        return profile;
    }

    public Path cwd() {
        return cwd;
    }

    public AgentConnection connection() {
        return connection;
    }

    // ---- Player-driven actions -------------------------------------------------------------

    public CompletableFuture<String> prompt(String text) {
        return connection.prompt(sessionId, text);
    }

    public void cancel() {
        connection.cancel(sessionId);
    }

    public void answerPermission(String requestId, String optionId) {
        CompletableFuture<String> future = pendingPermissions.remove(requestId);
        if (future != null) {
            future.complete(optionId);
        } else {
            log.debug("No pending permission {} for session {}", requestId, sessionId);
        }
    }

    public void shutdown() {
        pendingPermissions.values().forEach(f -> f.complete(null));
        pendingPermissions.clear();
        if (connection != null) {
            connection.close();
        }
    }

    // ---- ClientSideHandler (agent -> us) ---------------------------------------------------

    @Override
    public void onSessionUpdate(String sessionId, SessionUpdate update) {
        switch (update.kind()) {
            case "agent_message_chunk" -> emitMessage("assistant", update.text());
            case "agent_thought_chunk" -> emitMessage("thought", update.text());
            case "user_message_chunk" -> emitMessage("user", update.text());
            case "tool_call", "tool_call_update" -> toPlugin.accept(new BridgeMessage.ToolCall(
                    sessionId, update.toolCallId(), update.toolTitle(), update.toolKind(), update.toolStatus()));
            case "plan" -> toPlugin.accept(new BridgeMessage.Plan(sessionId, mapPlan(update.plan())));
            default -> log.debug("Unhandled session update kind: {}", update.kind());
        }
    }

    private void emitMessage(String role, String text) {
        if (text != null && !text.isEmpty()) {
            toPlugin.accept(new BridgeMessage.Message(sessionId, role, text));
        }
    }

    private static List<BridgeMessage.PlanItem> mapPlan(List<PlanEntry> entries) {
        return entries.stream()
                .map(e -> new BridgeMessage.PlanItem(e.content(), e.priority(), e.status()))
                .toList();
    }

    @Override
    public CompletableFuture<String> onRequestPermission(PermissionRequest request) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingPermissions.put(requestId, future);

        List<BridgeMessage.PermissionOptionDto> options = request.options().stream()
                .map(AgentSession::mapOption)
                .toList();
        toPlugin.accept(new BridgeMessage.PermissionRequest(
                sessionId, requestId, request.describe(), options));

        // Guard against an unanswered request wedging the agent forever.
        future.orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                .exceptionally(ex -> {
                    pendingPermissions.remove(requestId);
                    return null;
                });
        return future;
    }

    private static BridgeMessage.PermissionOptionDto mapOption(PermissionOption option) {
        return new BridgeMessage.PermissionOptionDto(option.optionId(), option.name(), option.kind());
    }

    @Override
    public String onReadTextFile(String sessionId, String path, Integer line, Integer limit) {
        try {
            Path resolved = resolve(path);
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int start = line == null ? 0 : Math.max(0, line - 1);
            int end = limit == null ? lines.size() : Math.min(lines.size(), start + limit);
            if (start >= lines.size()) {
                return "";
            }
            return String.join("\n", lines.subList(start, end));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void onWriteTextFile(String sessionId, String path, String content) {
        try {
            Path resolved = resolve(path);
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    private Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : cwd.resolve(p);
    }
}
