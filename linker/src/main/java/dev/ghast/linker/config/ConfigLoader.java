package dev.ghast.linker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Loads {@link LinkerConfig} from disk, writing a documented default file on first run. */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ConfigLoader() {
    }

    /** Loads the config at {@code path}, creating it from {@link #defaults()} if it does not exist. */
    public static LinkerConfig load(Path path) throws IOException {
        if (Files.notExists(path)) {
            LinkerConfig defaults = defaults();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            MAPPER.writeValue(path.toFile(), defaults);
            log.info("Wrote default config to {}", path.toAbsolutePath());
            return defaults;
        }
        LinkerConfig config = MAPPER.readValue(path.toFile(), LinkerConfig.class);
        log.info("Loaded config from {}", path.toAbsolutePath());
        return config;
    }

    /** The built-in default configuration: Claude Code and Codex over their npx ACP adapters. */
    public static LinkerConfig defaults() {
        AgentProfile claude = new AgentProfile(
                "claude-code",
                "Claude Code",
                "Anthropic Claude Code, wrapped for ACP",
                List.of("npx", "-y", "@zed-industries/claude-code-acp"),
                null,
                Map.of(),
                null);

        AgentProfile codex = new AgentProfile(
                "codex",
                "Codex",
                "OpenAI Codex, wrapped for ACP",
                List.of("npx", "-y", "@zed-industries/codex-acp"),
                null,
                Map.of(),
                null);

        return new LinkerConfig(
                "127.0.0.1",
                8765,
                null,
                "claude-code",
                System.getProperty("user.dir"),
                true,
                List.of(claude, codex));
    }
}
