package dev.ghast.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Handles an inbound JSON-RPC request (a call made by the remote peer to us).
 *
 * <p>The returned future completes with the {@code result} value to send back. Completing it
 * exceptionally with a {@link JsonRpcException} produces a structured error response; any other
 * throwable becomes a generic internal error.
 */
@FunctionalInterface
public interface RequestHandler {
    CompletableFuture<Object> handle(JsonNode params);
}
