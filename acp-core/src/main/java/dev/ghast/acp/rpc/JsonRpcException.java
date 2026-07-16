package dev.ghast.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/** Represents a JSON-RPC 2.0 error object returned by the peer. */
public class JsonRpcException extends RuntimeException {

    private final int code;
    private final transient JsonNode data;

    public JsonRpcException(int code, String message, JsonNode data) {
        super("JSON-RPC error " + code + ": " + message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }
}
