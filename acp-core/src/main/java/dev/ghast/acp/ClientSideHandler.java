package dev.ghast.acp;

import dev.ghast.acp.model.PermissionRequest;
import dev.ghast.acp.model.SessionUpdate;

import java.util.concurrent.CompletableFuture;

/**
 * Callbacks the agent invokes on the client during a session. Implemented by the host application
 * (here, the linker) to surface agent activity and answer the agent's requests.
 */
public interface ClientSideHandler {

    /** A streaming update for a session: message/thought chunk, tool call, plan, etc. */
    void onSessionUpdate(String sessionId, SessionUpdate update);

    /**
     * The agent is asking for permission to perform an action. Complete the returned future with
     * the chosen {@code optionId}, or {@code null} to signal the request was cancelled/declined
     * without a specific option.
     */
    CompletableFuture<String> onRequestPermission(PermissionRequest request);

    /** The agent wants to read a text file. Return its contents (optionally sliced by line/limit). */
    default String onReadTextFile(String sessionId, String path, Integer line, Integer limit) {
        throw new UnsupportedOperationException("fs/read_text_file not supported");
    }

    /** The agent wants to overwrite a text file with {@code content}. */
    default void onWriteTextFile(String sessionId, String path, String content) {
        throw new UnsupportedOperationException("fs/write_text_file not supported");
    }
}
