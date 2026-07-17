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
    private final SessionStore store;

    public SessionManager(LinkerConfig config, Consumer<BridgeMessage> toPlugin, Runnable onSessionsChanged,
                          SessionStore store) {
        this.config = config;
        this.toPlugin = toPlugin;
        this.onSessionsChanged = onSessionsChanged;
        this.store = store;
    }

    /** Outcome of establishing a session: its id and whether it reattached a prior one. */
    private record SessionOutcome(String sessionId, boolean resumed) {
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

        // Resume the villager's previous conversation if the plugin asked to, or if we have one on
        // record for this villager from a prior run.
        String resumeId = request.resumeSessionId() != null ? request.resumeSessionId()
                : store == null ? null : store.sessionIdFor(request.villagerId());

        try {
            AgentConnection connection = AgentConnection.spawn(
                    profile.command(),
                    cwd.toFile(),
                    profile.env() == null ? Map.of() : profile.env(),
                    session);
            session.attach(connection);
            connection.onClose(() -> handleAgentExit(session));

            String cwdStr = cwd.toString();
            connection.initialize()
                    .thenCompose(init -> maybeAuthenticate(connection, profile, init).thenApply(v -> init))
                    .thenCompose(init -> establishSession(connection, cwdStr, resumeId, init))
                    .whenComplete((outcome, error) -> finishCreate(session, outcome, error));
        } catch (Exception e) {
            log.warn("Failed to launch agent '{}' with command {} in {}: {}",
                    profile.id(), profile.command(), cwd, e.toString(), e);
            toPlugin.accept(new BridgeMessage.AgentError(null,
                    "Failed to launch " + profile.displayNameOrId() + ": " + e.getMessage()));
        }
    }

    private java.util.concurrent.CompletionStage<Void> maybeAuthenticate(
            AgentConnection connection, AgentProfile profile, InitializeResult init) {
        if (!init.requiresAuth()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        String method = profile.authMethod() != null ? profile.authMethod() : init.authMethods().get(0);
        log.info("Agent '{}' requires auth. Offered methods {}, using '{}'{}",
                profile.id(), init.authMethods(), method,
                profile.authMethod() != null ? " (from profile.authMethod)" : " (first offered)");
        // Some adapters (e.g. claude-code-acp) advertise an auth method but don't implement the
        // `authenticate` RPC — they rely on ambient credentials (an existing `claude` CLI login).
        // A failed authenticate must not abort boot: session/new is the real gate, and it surfaces a
        // clear error if credentials really are missing.
        return connection.authenticate(method)
                .<Void>thenApply(ok -> null)
                .exceptionally(err -> {
                    log.warn("authenticate('{}') did not complete for agent '{}': {}. Continuing to "
                            + "session/new — the agent may use ambient credentials (e.g. an existing "
                            + "`claude` login). If it isn't logged in, session/new will fail with details.",
                            method, profile.id(), rootMessage(err));
                    return null;
                });
    }

    /**
     * Resumes {@code resumeId} via {@code session/load} when possible, otherwise starts a fresh
     * session. If a resume is requested but fails (or the agent lacks the capability), falls back to a
     * new session so a stale/expired id never blocks the villager.
     */
    private java.util.concurrent.CompletionStage<SessionOutcome> establishSession(
            AgentConnection connection, String cwd, String resumeId, InitializeResult init) {
        if (resumeId != null && init.supportsLoadSession()) {
            log.info("Resuming session {} via session/load", resumeId);
            return connection.loadSession(resumeId, cwd)
                    .thenApply(id -> new SessionOutcome(id, true))
                    .exceptionallyCompose(err -> {
                        log.warn("Resume of session {} failed ({}); creating a fresh session",
                                resumeId, rootMessage(err));
                        return connection.newSession(cwd).thenApply(id -> new SessionOutcome(id, false));
                    });
        }
        if (resumeId != null) {
            log.info("Agent does not support session/load; starting a new session instead of resuming {}",
                    resumeId);
        }
        return connection.newSession(cwd).thenApply(id -> new SessionOutcome(id, false));
    }

    private void finishCreate(AgentSession session, SessionOutcome outcome, Throwable error) {
        String sessionId = outcome == null ? null : outcome.sessionId();
        if (error != null || sessionId == null) {
            String reason = error != null ? rootMessage(error) : "agent returned no session id";
            String diagnostics = agentDiagnostics(session);
            log.warn("Session setup failed for villager {}: {}{}",
                    session.villagerId(), reason, diagnostics);
            toPlugin.accept(new BridgeMessage.AgentError(null,
                    "Could not start " + session.profile().displayNameOrId() + ": " + reason));
            session.shutdown();
            return;
        }
        session.setSessionId(sessionId);
        sessions.put(sessionId, session);
        if (store != null) {
            store.put(session.villagerId(), sessionId, session.profile().id(), session.cwd().toString());
        }
        onSessionsChanged.run();
        log.info("Session {} {} for villager {} ({})",
                sessionId, outcome.resumed() ? "resumed" : "ready", session.villagerId(), session.profile().id());
        toPlugin.accept(new BridgeMessage.SessionCreated(
                session.villagerId(), sessionId, session.profile().id(), session.profile().displayNameOrId(),
                outcome.resumed()));
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
            // An explicit close means the villager is gone/reset — forget the resume binding so we
            // don't try to reattach a session the user deliberately ended.
            if (store != null) {
                store.remove(session.villagerId());
            }
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
        Path cwd = new File(chosen).getAbsoluteFile().toPath();
        // The agent subprocess is launched with this as its working directory; ProcessBuilder fails
        // if it does not exist, so create it (and any parents) on demand.
        try {
            java.nio.file.Files.createDirectories(cwd);
        } catch (java.io.IOException e) {
            log.warn("Could not create workspace directory {}: {}", cwd, e.getMessage());
        }
        return cwd;
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

    /**
     * A multi-line diagnostic block for a failed session: the agent's exit code (if it died) and the
     * tail of its stderr. Returns {@code ""} when there is nothing useful to add.
     */
    private static String agentDiagnostics(AgentSession session) {
        AgentConnection connection = session.connection();
        if (connection == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Integer exit = connection.exitCode();
        if (exit != null) {
            sb.append(System.lineSeparator())
                    .append("  agent process exited with code ").append(exit);
        }
        String stderr = connection.recentStderrText();
        if (stderr != null && !stderr.isBlank()) {
            sb.append(System.lineSeparator())
                    .append("  --- agent stderr (last ").append(stderr.lines().count()).append(" lines) ---")
                    .append(System.lineSeparator())
                    .append(stderr.stripTrailing());
        }
        return sb.toString();
    }
}
