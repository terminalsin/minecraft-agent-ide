package dev.ghast.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/** Handles an inbound JSON-RPC notification (a fire-and-forget message with no {@code id}). */
@FunctionalInterface
public interface NotificationHandler {
    void handle(JsonNode params);
}
