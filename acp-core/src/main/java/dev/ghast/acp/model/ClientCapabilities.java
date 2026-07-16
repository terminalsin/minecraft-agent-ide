package dev.ghast.acp.model;

/**
 * Capabilities the client advertises to the agent during {@code initialize}.
 *
 * @param fs       filesystem capabilities the agent may use via {@code fs/*} requests
 * @param terminal whether the client can host terminals for the agent (unsupported here)
 */
public record ClientCapabilities(FsCapability fs, boolean terminal) {

    public static ClientCapabilities filesystemOnly() {
        return new ClientCapabilities(new FsCapability(true, true), false);
    }
}
