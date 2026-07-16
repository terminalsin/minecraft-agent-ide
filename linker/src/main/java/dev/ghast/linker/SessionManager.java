package dev.ghast.linker;

import dev.ghast.acp.AgentConnection;
import dev.ghast.acp.model.InitializeResult;
import dev.ghast.bridge.BridgeMessage;
import dev.ghast.linker.config.AgentProfile;
import dev.ghast.linker.config.LinkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Owns the set of active {@link AgentSession}s and dispatches inbound {@link BridgeMessage}s from
 * the Minecraft plugin. One agent subprocess is spawned per villager session.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final String LINKER_VERSION = "0.1.0";

    private final LinkerConfig config;
    private final Consumer<BridgeMessage> toPlugin;
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "linker-session-worker");
        t.setDaemon(true);
        return t;
    });
    private final Runnable onSessionsChanged;

    public SessionManager(LinkerConfig config, Consumer<BridgeMessage> toPlugin, Runnable onSessionsChanged) {
        this.config = config;
        this.toPlugin = toPlugin;
        this.onSessionsChanged = onSessionsChanged;
    }

    public int activeSessions() {
        return sessions.size();
    }

    public List<AgentSession> sessions() {
        return List.copyOf(sessions.values());
    }

    /** Dispatches a message received from the plugin. */
    public void handle(BridgeMessage message) {
        switch (message) {
            case BridgeMessage.Hello hello -> sendWelcome(hello);
            case BridgeMessage.CreateSession create ->
                    workers.execute(() -> createSession(create));
            case BridgeMessage.Prompt prompt -> handlePrompt(prompt);
            case BridgeMessage.Cancel cancel -> withSession(cancel.sessionId(), AgentSession::cancel);
            case BridgeMessage.PermissionResponse response -> withSession(response.sessionId(),
                    s -> s.answerPermission(response.requestId(), response.optionId()));
            case BridgeMessage.CloseSession close -> closeSession(close.sessionId());
            default -> log.debug("Ignoring plugin message type: {}", message.getClass().getSimpleName());
        }
    }

    private void sendWelcome(BridgeMessage.Hello hello) {
        log.info("Plugin connected (version {})", hello.pluginVersion());
        List<BridgeMessage.AgentProfileInfo> agents = config.agents().stream()
                .map(a -> new BridgeMessage.AgentProfileInfo(a.id(), a.displayNameOrId(), a.description()))
                .toList();
        toPlugin.accept(new BridgeMessage.Welcome(LINKER_VERSION, agents, config.defaultProfile()));
    }

    private void handlePrompt(BridgeMessage.Prompt prompt) {
        AgentSession session = sessions.get(prompt.sessionId());
        if (session == null) {
            toPlugin.accept(new BridgeMessage.AgentError(prompt.sessionId(), "No such session"));
            return;
        }
        session.prompt(prompt.text()).whenComplete((stopReason, error) -> {
            if (error != null) {
                log.warn("Prompt failed for session {}: {}", prompt.sessionId(), error.getMessage());
                toPlugin.accept(new BridgeMessage.AgentError(prompt.sessionId(),
                        "Prompt failed: " + rootMessage(error)));
            } else {
                toPlugin.accept(new BridgeMessage.TurnEnd(prompt.sessionId(), stopReason));
            }
        });
    }

    private void createSession(BridgeMessage.CreateSession request) {
        AgentProfile profile = config.resolveProfile(request.agentProfile());
        if (profile == null) {
            toPlugin.accept(new BridgeMessage.AgentError(null, "No agent profiles are configured"));
            return;
        }
        Path cwd = resolveCwd(request.cwd(), profile);
        AgentSession session = new AgentSession(request.villagerId(), profile, cwd, toPlugin);

        try {
            AgentConnection connection = AgentConnection.spawn(
                    profile.command(),
                    cwd.toFile(),
                    profile.env() == null ? Map.of() : profile.env(),
                    session);
            session.attach(connection);
            connection.onClose(() -> handleAgentExit(session));

            connection.initialize()
                    .thenCompose(init -> maybeAuthenticate(connection, profile, init))
                    .thenCompose(ignored -> connection.newSession(cwd.toString()))
                    .whenComplete((sessionId, error) -> finishCreate(session, sessionId, error));
        } catch (Exception e) {
            log.warn("Failed to launch agent '{}': {}", profile.id(), e.getMessage());
            toPlugin.accept(new BridgeMessage.AgentError(null,
                    "Failed to launch " + profile.displayNameOrId() + ": " + e.getMessage()));
        }
    }

    private java.util.concurrent.CompletionStage<Void> maybeAuthenticate(
            AgentConnection connection, AgentProfile profile, InitializeResult init) {
        if (init.requiresAuth()) {
            String method = profile.authMethod() != null ? profile.authMethod() : init.authMethods().get(0);
            log.info("Agent '{}' requires auth, using method '{}'", profile.id(), method);
            return connection.authenticate(method);
        }
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    private void finishCreate(AgentSession session, String sessionId, Throwable error) {
        if (error != null || sessionId == null) {
            String reason = error != null ? rootMessage(error) : "agent returned no session id";
            log.warn("Session setup failed for villager {}: {}", session.villagerId(), reason);
            toPlugin.accept(new BridgeMessage.AgentError(null,
                    "Could not start " + session.profile().displayNameOrId() + ": " + reason));
            session.shutdown();
            return;
        }
        session.setSessionId(sessionId);
        sessions.put(sessionId, session);
        onSessionsChanged.run();
        log.info("Session {} ready for villager {} ({})",
                sessionId, session.villagerId(), session.profile().id());
        toPlugin.accept(new BridgeMessage.SessionCreated(
                session.villagerId(), sessionId, session.profile().id(), session.profile().displayNameOrId()));
    }

    private void handleAgentExit(AgentSession session) {
        String sessionId = session.sessionId();
        if (sessionId != null && sessions.remove(sessionId) != null) {
            onSessionsChanged.run();
            log.info("Agent for session {} exited", sessionId);
            toPlugin.accept(new BridgeMessage.AgentError(sessionId, "The agent process ended"));
        }
    }

    private void closeSession(String sessionId) {
        AgentSession session = sessions.remove(sessionId);
        if (session != null) {
            onSessionsChanged.run();
            session.shutdown();
            log.info("Closed session {}", sessionId);
        }
    }

    private void withSession(String sessionId, Consumer<AgentSession> action) {
        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            action.accept(session);
        }
    }

    private Path resolveCwd(String requested, AgentProfile profile) {
        String chosen = requested != null ? requested
                : profile.cwd() != null ? profile.cwd()
                : config.defaultCwd() != null ? config.defaultCwd()
                : System.getProperty("user.dir");
        return new File(chosen).getAbsoluteFile().toPath();
    }

    public void shutdown() {
        sessions.values().forEach(AgentSession::shutdown);
        sessions.clear();
        workers.shutdownNow();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
