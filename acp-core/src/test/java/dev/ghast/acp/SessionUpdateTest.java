package dev.ghast.acp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ghast.acp.json.Json;
import dev.ghast.acp.model.SessionUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionUpdateTest {

    private static JsonNode parse(String json) throws Exception {
        return Json.MAPPER.readTree(json);
    }

    @Test
    void parsesAgentMessageChunk() throws Exception {
        JsonNode update = parse("""
                {"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello world"}}
                """);
        SessionUpdate parsed = SessionUpdate.parse(update);
        assertEquals("agent_message_chunk", parsed.kind());
        assertEquals("Hello world", parsed.text());
    }

    @Test
    void parsesToolCall() throws Exception {
        JsonNode update = parse("""
                {"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read file","kind":"read","status":"pending"}
                """);
        SessionUpdate parsed = SessionUpdate.parse(update);
        assertEquals("tool_call", parsed.kind());
        assertEquals("t1", parsed.toolCallId());
        assertEquals("Read file", parsed.toolTitle());
        assertEquals("pending", parsed.toolStatus());
    }

    @Test
    void parsesPlan() throws Exception {
        JsonNode update = parse("""
                {"sessionUpdate":"plan","entries":[
                  {"content":"Step one","priority":"high","status":"in_progress"},
                  {"content":"Step two","priority":"low","status":"pending"}
                ]}
                """);
        SessionUpdate parsed = SessionUpdate.parse(update);
        assertEquals("plan", parsed.kind());
        assertEquals(2, parsed.plan().size());
        assertEquals("Step one", parsed.plan().get(0).content());
        assertNull(parsed.text());
    }

    @Test
    void extractsTextFromContentArray() throws Exception {
        JsonNode content = parse("""
                [{"type":"text","text":"foo "},{"type":"text","text":"bar"}]
                """);
        assertEquals("foo bar", SessionUpdate.extractText(content));
    }
}
