package dev.ghast.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/** Represents a JSON-RPC 2.0 error object returned by the peer. */
public class JsonRpcException extends RuntimeException {

    private final int code;
    private final transient JsonNode data;

    public JsonRpcException(int code, String message, JsonNode data) {
        super(buildMessage(code, message, data));
        this.code = code;
        this.data = data;
    }

    /**
     * Builds a message that also surfaces the JSON-RPC {@code data} field. Agents routinely put the
     * real cause (a nested message or stack) there, so dropping it turns every failure into an opaque
     * "Internal error".
     */
    private static String buildMessage(int code, String message, JsonNode data) {
        String base = "JSON-RPC error " + code + ": " + message;
        if (data == null || data.isNull()) {
            return base;
        }
        // Prefer a human-readable nested message when the agent provides one.
        JsonNode nested = data.get("message");
        if (nested == null && data.has("error")) {
            nested = data.get("error");
        }
        if (nested != null && nested.isTextual()) {
            return base + " (" + nested.asText() + ")";
        }
        return base + " | data: " + data;
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }
}
