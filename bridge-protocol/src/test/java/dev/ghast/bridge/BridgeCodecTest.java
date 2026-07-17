package dev.ghast.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeCodecTest {

    private final BridgeCodec codec = BridgeCodec.INSTANCE;

    @Test
    void roundTripsPrompt() {
        BridgeMessage original = new BridgeMessage.Prompt("s1", "hello agent");
        String json = codec.encode(original);
        assertTrue(json.contains("\"type\":\"prompt\""));
        assertEquals(original, codec.decode(json));
    }

    @Test
    void roundTripsPermissionRequest() {
        BridgeMessage original = new BridgeMessage.PermissionRequest(
                "s1", "req-7", "Write file foo.txt",
                List.of(new BridgeMessage.PermissionOptionDto("allow", "Allow", "allow_once"),
                        new BridgeMessage.PermissionOptionDto("deny", "Reject", "reject_once")));
        BridgeMessage decoded = codec.decode(codec.encode(original));
        assertInstanceOf(BridgeMessage.PermissionRequest.class, decoded);
        assertEquals(original, decoded);
    }

    @Test
    void omitsNullFields() {
        String json = codec.encode(new BridgeMessage.CreateSession("v1", null, null, null));
        assertTrue(json.contains("\"villagerId\":\"v1\""));
        assertTrue(!json.contains("agentProfile"), "null fields should be omitted");
    }
}
