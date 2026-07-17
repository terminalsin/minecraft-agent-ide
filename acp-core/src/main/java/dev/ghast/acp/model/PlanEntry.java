package dev.ghast.acp.model;

/**
 * One entry of an agent plan reported via a {@code plan} session update.
 *
 * @param content  the step description
 * @param priority priority hint ({@code high}, {@code medium}, {@code low})
 * @param status   lifecycle status ({@code pending}, {@code in_progress}, {@code completed})
 */
public record PlanEntry(String content, String priority, String status) {
}
