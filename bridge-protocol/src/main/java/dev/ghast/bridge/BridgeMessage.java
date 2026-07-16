package dev.ghast.bridge;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * The full set of messages exchanged over the plugin&lt;-&gt;linker WebSocket link. Each concrete
 * message is a record tagged by a {@code type} discriminator, so Jackson can round-trip the sealed
 * hierarchy polymorphically on both ends.
 *
 * <p>The naming convention: {@code C2L} = client (Minecraft plugin) to linker, {@code L2C} = linker
 * to client. The plugin deals only in game concepts (villagers, chat, buttons); the linker
 * translates those to and from ACP.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BridgeMessage.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = BridgeMessage.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = BridgeMessage.CreateSession.class, name = "create_session"),
        @JsonSubTypes.Type(value = BridgeMessage.SessionCreated.class, name = "session_created"),
        @JsonSubTypes.Type(value = BridgeMessage.Prompt.class, name = "prompt"),
        @JsonSubTypes.Type(value = BridgeMessage.Cancel.class, name = "cancel"),
        @JsonSubTypes.Type(value = BridgeMessage.CloseSession.class, name = "close_session"),
        @JsonSubTypes.Type(value = BridgeMessage.Message.class, name = "message"),
        @JsonSubTypes.Type(value = BridgeMessage.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = BridgeMessage.Plan.class, name = "plan"),
        @JsonSubTypes.Type(value = BridgeMessage.PermissionRequest.class, name = "permission_request"),
        @JsonSubTypes.Type(value = BridgeMessage.PermissionResponse.class, name = "permission_response"),
        @JsonSubTypes.Type(value = BridgeMessage.TurnEnd.class, name = "turn_end"),
        @JsonSubTypes.Type(value = BridgeMessage.AgentError.class, name = "agent_error"),
})
public sealed interface BridgeMessage {

    // ---- Handshake -------------------------------------------------------------------------

    /** C2L: sent immediately after connect so the linker knows the plugin version. */
    record Hello(String pluginVersion) implements BridgeMessage {
    }

    /** L2C: reply to {@link Hello}; advertises available agent profiles. */
    record Welcome(String linkerVersion, List<AgentProfileInfo> agents, String defaultProfile)
            implements BridgeMessage {
    }

    // ---- Session lifecycle -----------------------------------------------------------------

    /**
     * C2L: bind a villager to a new agent session.
     *
     * @param villagerId   the plugin's id for the villager NPC (echoed back)
     * @param agentProfile which configured agent to launch ({@code null} = linker default)
     * @param cwd          workspace directory for the session ({@code null} = profile/linker default)
     */
    record CreateSession(String villagerId, String agentProfile, String cwd) implements BridgeMessage {
    }

    /** L2C: a session was established for the villager. */
    record SessionCreated(String villagerId, String sessionId, String agentProfile, String agentName)
            implements BridgeMessage {
    }

    /** C2L: send a user turn to the agent. */
    record Prompt(String sessionId, String text) implements BridgeMessage {
    }

    /** C2L: cancel the in-flight turn. */
    record Cancel(String sessionId) implements BridgeMessage {
    }

    /** C2L: end the session and stop the agent if unused. */
    record CloseSession(String sessionId) implements BridgeMessage {
    }

    // ---- Streaming agent activity (L2C) ----------------------------------------------------

    /**
     * L2C: a chunk of agent output.
     *
     * @param role one of {@code assistant}, {@code thought}, {@code user}
     */
    record Message(String sessionId, String role, String text) implements BridgeMessage {
    }

    /** L2C: a tool call started or changed state. */
    record ToolCall(String sessionId, String toolCallId, String title, String kind, String status)
            implements BridgeMessage {
    }

    /** L2C: the agent's current plan. */
    record Plan(String sessionId, List<PlanItem> entries) implements BridgeMessage {
    }

    /** L2C: the agent needs permission; the plugin should present the options to the player. */
    record PermissionRequest(String sessionId, String requestId, String title, List<PermissionOptionDto> options)
            implements BridgeMessage {
    }

    /** C2L: the player's answer to a {@link PermissionRequest}; {@code optionId} null = cancelled. */
    record PermissionResponse(String sessionId, String requestId, String optionId) implements BridgeMessage {
    }

    /** L2C: the current turn finished. */
    record TurnEnd(String sessionId, String stopReason) implements BridgeMessage {
    }

    /** L2C: something went wrong for the session (or globally if sessionId is null). */
    record AgentError(String sessionId, String message) implements BridgeMessage {
    }

    // ---- Value objects ---------------------------------------------------------------------

    record AgentProfileInfo(String id, String displayName, String description) {
    }

    record PermissionOptionDto(String optionId, String name, String kind) {
    }

    record PlanItem(String content, String priority, String status) {
    }
}
