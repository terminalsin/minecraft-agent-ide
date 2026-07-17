package dev.ghast.acp.model;

/**
 * A single choice offered to the client when the agent requests permission to run a tool.
 *
 * @param optionId stable id echoed back in the permission outcome
 * @param name     human-readable label (e.g. "Allow", "Reject")
 * @param kind     semantic hint such as {@code allow_once}, {@code allow_always}, {@code reject_once}
 */
public record PermissionOption(String optionId, String name, String kind) {
}
