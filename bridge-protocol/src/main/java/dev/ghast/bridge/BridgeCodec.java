package dev.ghast.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes {@link BridgeMessage} values to and from the JSON text sent over the WebSocket.
 * A single shared, thread-safe instance is exposed as {@link #INSTANCE}.
 */
public final class BridgeCodec {

    public static final BridgeCodec INSTANCE = new BridgeCodec();

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String encode(BridgeMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to encode bridge message: " + message, e);
        }
    }

    public BridgeMessage decode(String json) {
        try {
            return mapper.readValue(json, BridgeMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to decode bridge message: " + json, e);
        }
    }
}
