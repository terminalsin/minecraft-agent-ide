package dev.ghast.linker.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A configured ACP agent the linker can launch on demand.
 *
 * @param id          stable identifier referenced by the plugin (e.g. {@code claude-code})
 * @param displayName human-friendly name shown in game
 * @param description one-line description
 * @param command     process command line, e.g. {@code ["npx","-y","@zed-industries/claude-code-acp"]}
 * @param cwd         default working directory for sessions of this agent ({@code null} = linker default)
 * @param env         extra environment variables to pass to the agent process
 * @param authMethod  ACP auth method id to use if the agent requires authentication ({@code null} = none)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentProfile(
        String id,
        String displayName,
        String description,
        List<String> command,
        String cwd,
        Map<String, String> env,
        String authMethod) {

    public String displayNameOrId() {
        return displayName != null && !displayName.isBlank() ? displayName : id;
    }
}
