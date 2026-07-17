package dev.ghast.acp.model;

/**
 * Filesystem capabilities the client offers the agent.
 *
 * @param readTextFile  whether the agent may call {@code fs/read_text_file}
 * @param writeTextFile whether the agent may call {@code fs/write_text_file}
 */
public record FsCapability(boolean readTextFile, boolean writeTextFile) {
}
