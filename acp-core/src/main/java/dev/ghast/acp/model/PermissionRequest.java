package dev.ghast.acp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A decoded {@code session/request_permission} request from the agent.
 *
 * @param sessionId session the request belongs to
 * @param toolCall  the raw tool-call object describing what the agent wants to do
 * @param options   choices the client may respond with
 */
public record PermissionRequest(String sessionId, JsonNode toolCall, List<PermissionOption> options) {

    /** Best-effort human-readable description of the tool call (its {@code title}, if present). */
    public String describe() {
        if (toolCall == null) {
            return "a tool call";
        }
        JsonNode title = toolCall.get("title");
        if (title != null && !title.isNull()) {
            return title.asText();
        }
        JsonNode kind = toolCall.get("kind");
        return kind != null && !kind.isNull() ? kind.asText() : "a tool call";
    }
}
