package dev.ghast.acp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent's response to {@code initialize}.
 *
 * @param protocolVersion  version the agent agreed to
 * @param agentCapabilities raw capabilities object (e.g. {@code loadSession}, prompt capabilities)
 * @param authMethods      ids of authentication methods the agent supports, if any
 */
public record InitializeResult(int protocolVersion, JsonNode agentCapabilities, List<String> authMethods) {

    public static InitializeResult parse(JsonNode result) {
        int version = result.path("protocolVersion").asInt(1);
        JsonNode caps = result.get("agentCapabilities");
        List<String> methods = new ArrayList<>();
        JsonNode auth = result.get("authMethods");
        if (auth != null && auth.isArray()) {
            for (JsonNode m : auth) {
                JsonNode id = m.get("id");
                methods.add(id != null ? id.asText() : m.asText());
            }
        }
        return new InitializeResult(version, caps, methods);
    }

    public boolean supportsLoadSession() {
        return agentCapabilities != null && agentCapabilities.path("loadSession").asBoolean(false);
    }

    public boolean requiresAuth() {
        return !authMethods.isEmpty();
    }
}
