package dev.ghast.acp.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ghast.acp.AcpConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * A normalized, typed view over the many shapes of an ACP {@code session/update} payload.
 *
 * <p>Only the fields relevant to a given {@link #kind()} are populated; the rest are {@code null}
 * or empty. The original node is retained via {@link #raw()} for anything not modelled here.
 */
public record SessionUpdate(
        String kind,
        String text,
        String toolCallId,
        String toolTitle,
        String toolKind,
        String toolStatus,
        String toolName,
        String toolDetail,
        List<PlanEntry> plan,
        JsonNode raw
) {

    /** Parses the {@code update} object nested inside a {@code session/update} notification. */
    public static SessionUpdate parse(JsonNode update) {
        if (update == null) {
            return new SessionUpdate("unknown", null, null, null, null, null, null, null, List.of(), null);
        }
        String kind = update.path("sessionUpdate").asText("unknown");

        String text = null;
        String toolCallId = null;
        String toolTitle = null;
        String toolKind = null;
        String toolStatus = null;
        String toolName = null;
        String toolDetail = null;
        List<PlanEntry> plan = List.of();

        switch (kind) {
            case AcpConstants.UPDATE_AGENT_MESSAGE_CHUNK,
                 AcpConstants.UPDATE_AGENT_THOUGHT_CHUNK,
                 AcpConstants.UPDATE_USER_MESSAGE_CHUNK ->
                    text = extractText(update.get("content"));
            case AcpConstants.UPDATE_TOOL_CALL, AcpConstants.UPDATE_TOOL_CALL_UPDATE -> {
                toolCallId = textOrNull(update, "toolCallId");
                toolTitle = textOrNull(update, "title");
                toolKind = textOrNull(update, "kind");
                toolStatus = textOrNull(update, "status");
                toolName = extractToolName(update);
                toolDetail = extractToolDetail(update);
            }
            case AcpConstants.UPDATE_PLAN -> plan = extractPlan(update.get("entries"));
            default -> {
                // Leave the specialised fields null; callers may inspect raw().
            }
        }
        return new SessionUpdate(kind, text, toolCallId, toolTitle, toolKind, toolStatus,
                toolName, toolDetail, plan, update);
    }

    /** The concrete tool name the agent reports out-of-band, e.g. {@code _meta.claudeCode.toolName}. */
    private static String extractToolName(JsonNode update) {
        JsonNode name = update.path("_meta").path("claudeCode").path("toolName");
        if (name.isTextual()) {
            // claude-code-acp prefixes MCP tools ("mcp__acp__Read"); show the last, human segment.
            String raw = name.asText();
            int idx = raw.lastIndexOf("__");
            return idx >= 0 && idx + 2 < raw.length() ? raw.substring(idx + 2) : raw;
        }
        return null;
    }

    /** A short, human-readable summary of the tool's input (command, path, pattern, …), if present. */
    private static String extractToolDetail(JsonNode update) {
        JsonNode input = update.get("rawInput");
        if (input != null && input.isObject()) {
            for (String field : new String[]{"command", "file_path", "path", "pattern", "query",
                    "url", "old_string", "prompt", "description"}) {
                JsonNode value = input.get(field);
                if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                    return clip(value.asText(), 120);
                }
            }
        }
        // Fall back to any text content block attached to the call.
        String content = extractText(update.get("content"));
        return content == null ? null : clip(content, 120);
    }

    private static String clip(String value, int max) {
        String single = value.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    /** Extracts display text from a content block or a list/array of content blocks. */
    public static String extractText(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            content.forEach(part -> {
                String t = extractText(part);
                if (t != null) {
                    sb.append(t);
                }
            });
            return sb.isEmpty() ? null : sb.toString();
        }
        JsonNode text = content.get("text");
        if (text != null && !text.isNull()) {
            return text.asText();
        }
        // Some agents wrap chunks as {content: [...]}.
        JsonNode nested = content.get("content");
        return nested != null ? extractText(nested) : null;
    }

    private static List<PlanEntry> extractPlan(JsonNode entries) {
        if (entries == null || !entries.isArray()) {
            return List.of();
        }
        List<PlanEntry> out = new ArrayList<>();
        for (JsonNode entry : entries) {
            out.add(new PlanEntry(
                    textOrNull(entry, "content"),
                    textOrNull(entry, "priority"),
                    textOrNull(entry, "status")));
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }
}
