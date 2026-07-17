package dev.ghast.linker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * The built-in default configuration: Claude Code and Codex over their npx ACP adapters.
     *
     * <p>Commands and environment are auto-resolved from the local installation so the linker works
     * even when launched with a minimal {@code PATH} (e.g. from a GUI or a detached process): the
     * {@code npx} launcher is pinned to its absolute path, and the agent process inherits a {@code PATH}
     * that includes wherever {@code npx}/{@code node}/{@code claude}/{@code codex} actually live so the
     * ACP adapter can find the underlying CLI.
     *
     * <p>The default workspace is {@code <current working directory>/claudework}.
     */
    public static LinkerConfig defaults() {
        String npx = resolveExecutable("npx");
        String launcher = npx != null ? npx : "npx";
        Map<String, String> agentEnv = defaultAgentEnv();

        AgentProfile claude = new AgentProfile(
                "claude-code",
                "Claude Code",
                "Anthropic Claude Code, wrapped for ACP",
                List.of(launcher, "-y", "@zed-industries/claude-code-acp"),
                null,
                agentEnv,
                null);

        AgentProfile codex = new AgentProfile(
                "codex",
                "Codex",
                "OpenAI Codex, wrapped for ACP",
                List.of(launcher, "-y", "@zed-industries/codex-acp"),
                null,
                agentEnv,
                null);

        String defaultCwd = Path.of(System.getProperty("user.dir"), "claudework")
                .toAbsolutePath().toString();

        return new LinkerConfig(
                "127.0.0.1",
                8765,
                null,
                "claude-code",
                defaultCwd,
                true,
                List.of(claude, codex));
    }

    /**
     * The default environment passed to every agent subprocess: an install-aware {@code PATH}, plus
     * {@code null} entries that <em>strip</em> variables which would otherwise break the agent. In
     * particular, if the linker is launched from inside a Claude Code session, {@code CLAUDECODE} and
     * friends are inherited and the spawned {@code claude} refuses to nest — so we remove them.
     * ({@code null} value = unset; see {@code AgentConnection.spawn}.)
     */
    private static Map<String, String> defaultAgentEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", installPath());
        env.put("CLAUDECODE", null);
        env.put("CLAUDE_CODE_ENTRYPOINT", null);
        env.put("CLAUDE_CODE_SSE_PORT", null);
        return env;
    }

    /** Directories to search for installed tooling, on top of {@code PATH}. */
    private static List<String> searchDirs() {
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        // Common install locations that a minimal/GUI-launched PATH often misses.
        String home = System.getProperty("user.home");
        if (home != null) {
            dirs.add(home + "/.local/bin");
            dirs.add(home + "/.bun/bin");
            dirs.add(home + "/.volta/bin");
            dirs.add(home + "/.nvm/current/bin");
            dirs.add(home + "/n/bin");
        }
        dirs.add("/opt/homebrew/bin");
        dirs.add("/usr/local/bin");
        dirs.add("/usr/bin");
        dirs.add("/bin");
        return dirs;
    }

    /** Absolute path of an executable found in {@link #searchDirs()}, or {@code null} if not found. */
    private static String resolveExecutable(String name) {
        for (String dir : searchDirs()) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /**
     * A {@code PATH} value that puts the directories of the installed agent tooling first, then the
     * linker's own {@code PATH}. Passed to agent subprocesses so the ACP adapter can locate its CLI.
     */
    private static String installPath() {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        for (String tool : List.of("npx", "node", "claude", "codex")) {
            String resolved = resolveExecutable(tool);
            if (resolved != null) {
                Path parent = Path.of(resolved).getParent();
                if (parent != null) {
                    dirs.add(parent.toString());
                }
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            dirs.add(home + "/.local/bin");
        }
        dirs.add("/opt/homebrew/bin");
        dirs.add("/usr/local/bin");
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        return String.join(File.pathSeparator, dirs);
    }
}
