package dev.ghast.linker.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * Top-level linker configuration, loaded from {@code linker.json}.
 *
 * @param host          address the WebSocket bridge binds to
 * @param port          port the WebSocket bridge listens on
 * @param authToken     optional shared secret the plugin must present as {@code ?token=} ({@code null} = open)
 * @param defaultProfile id of the agent profile used when the plugin doesn't specify one
 * @param defaultCwd    workspace directory used when neither request nor profile specify one
 * @param ui            whether to show the Swing status window (ignored in headless environments)
 * @param agents        available agent profiles
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LinkerConfig(
        String host,
        int port,
        String authToken,
        String defaultProfile,
        String defaultCwd,
        boolean ui,
        List<AgentProfile> agents) {

    public Optional<AgentProfile> profile(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return agents.stream().filter(a -> id.equals(a.id())).findFirst();
    }

    public AgentProfile resolveProfile(String requested) {
        return profile(requested)
                .or(() -> profile(defaultProfile))
                .orElseGet(() -> agents.isEmpty() ? null : agents.get(0));
    }
}
