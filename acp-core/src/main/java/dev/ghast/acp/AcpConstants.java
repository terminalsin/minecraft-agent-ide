package dev.ghast.acp;

/** Method names, notification names and constants defined by the Agent Client Protocol. */
public final class AcpConstants {

    /** The ACP wire protocol version this client implements. */
    public static final int PROTOCOL_VERSION = 1;

    // Client -> Agent methods.
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_AUTHENTICATE = "authenticate";
    public static final String METHOD_SESSION_NEW = "session/new";
    public static final String METHOD_SESSION_LOAD = "session/load";
    public static final String METHOD_SESSION_PROMPT = "session/prompt";
    public static final String METHOD_SESSION_CANCEL = "session/cancel";

    // Agent -> Client methods / notifications.
    public static final String METHOD_SESSION_UPDATE = "session/update";
    public static final String METHOD_REQUEST_PERMISSION = "session/request_permission";
    public static final String METHOD_FS_READ_TEXT_FILE = "fs/read_text_file";
    public static final String METHOD_FS_WRITE_TEXT_FILE = "fs/write_text_file";

    // session/update discriminator values (field name: "sessionUpdate").
    public static final String UPDATE_AGENT_MESSAGE_CHUNK = "agent_message_chunk";
    public static final String UPDATE_AGENT_THOUGHT_CHUNK = "agent_thought_chunk";
    public static final String UPDATE_USER_MESSAGE_CHUNK = "user_message_chunk";
    public static final String UPDATE_TOOL_CALL = "tool_call";
    public static final String UPDATE_TOOL_CALL_UPDATE = "tool_call_update";
    public static final String UPDATE_PLAN = "plan";
    public static final String UPDATE_AVAILABLE_COMMANDS = "available_commands_update";

    // Common stopReason values from session/prompt.
    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_MAX_TOKENS = "max_tokens";
    public static final String STOP_MAX_TURN_REQUESTS = "max_turn_requests";
    public static final String STOP_REFUSAL = "refusal";
    public static final String STOP_CANCELLED = "cancelled";

    private AcpConstants() {
    }
}
