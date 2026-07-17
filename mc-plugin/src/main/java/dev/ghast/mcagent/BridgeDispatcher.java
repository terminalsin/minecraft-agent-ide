package dev.ghast.mcagent;

import dev.ghast.bridge.BridgeMessage;
import dev.ghast.mcagent.model.AgentVillager;
import dev.ghast.mcagent.render.DisplayRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies inbound {@link BridgeMessage}s from the linker to the game world. Every method here runs
 * on the main server thread (the caller marshals from the WebSocket thread).
 */
public final class BridgeDispatcher {

    private final AgentIdePlugin plugin;
    private final VillagerManager villagers;
    private final DisplayRenderer renderer;
    private final boolean showThoughts;

    private volatile List<BridgeMessage.AgentProfileInfo> profiles = List.of();
    private volatile String defaultProfile = "";
    // requestId -> sessionId, so the /agent perm command knows which session to answer.
    private final Map<String, String> pendingPermissions = new ConcurrentHashMap<>();

    public BridgeDispatcher(AgentIdePlugin plugin, VillagerManager villagers,
                            DisplayRenderer renderer, boolean showThoughts) {
        this.plugin = plugin;
        this.villagers = villagers;
        this.renderer = renderer;
        this.showThoughts = showThoughts;
    }

    public List<BridgeMessage.AgentProfileInfo> profiles() {
        return profiles;
    }

    public String defaultProfile() {
        return defaultProfile;
    }

    public String sessionForPermission(String requestId) {
        return pendingPermissions.remove(requestId);
    }

    public void handle(BridgeMessage message) {
        switch (message) {
            case BridgeMessage.Welcome welcome -> onWelcome(welcome);
            case BridgeMessage.SessionCreated created -> onSessionCreated(created);
            case BridgeMessage.Message msg -> onMessage(msg);
            case BridgeMessage.ToolCall tool -> onToolCall(tool);
            case BridgeMessage.Plan plan -> onPlan(plan);
            case BridgeMessage.PermissionRequest request -> onPermissionRequest(request);
            case BridgeMessage.TurnEnd end -> onTurnEnd(end);
            case BridgeMessage.AgentError error -> onError(error);
            default -> { /* client->linker messages never arrive here */ }
        }
    }

    private void onWelcome(BridgeMessage.Welcome welcome) {
        this.profiles = welcome.agents() == null ? List.of() : welcome.agents();
        this.defaultProfile = welcome.defaultProfile() == null ? "" : welcome.defaultProfile();
        plugin.getLogger().info("Linker " + welcome.linkerVersion() + " ready with agents: "
                + profiles.stream().map(BridgeMessage.AgentProfileInfo::id).toList());
        // Reattach any villagers restored from a previous run now that the linker is available.
        plugin.resumePendingSessions();
    }

    private void onSessionCreated(BridgeMessage.SessionCreated created) {
        Optional<AgentVillager> found = villagers.byVillagerId(created.villagerId());
        if (found.isEmpty()) {
            return;
        }
        AgentVillager agent = found.get();
        agent.setSessionId(created.sessionId());
        agent.setAgentName(created.agentName());
        agent.setState(AgentVillager.State.READY);
        agent.setNeedsResume(false);
        villagers.indexSession(agent);
        plugin.persistAgentMeta(agent);

        villagers.entityOf(agent).ifPresent(v -> {
            renderer.ensureHologram(agent, v);
            renderer.setNamePlate(v, agent, "ready");
            renderer.refresh(agent);
        });
        String verb = created.resumed() ? "Reconnected — I remember where we left off."
                : "I'm ready — just type to chat with me.";
        renderer.toConversers(agent, prefix(agent).append(Component.text(verb, NamedTextColor.GREEN)));
    }

    private void onMessage(BridgeMessage.Message msg) {
        villagers.bySession(msg.sessionId()).ifPresent(agent -> {
            switch (msg.role()) {
                case "assistant" -> {
                    agent.appendTurn(msg.text());
                    agent.setState(AgentVillager.State.WORKING);
                    agent.setLastLine(agent.currentTurn());
                    renderer.refresh(agent);
                }
                case "thought" -> {
                    if (showThoughts) {
                        agent.setState(AgentVillager.State.THINKING);
                        agent.setLastLine(msg.text());
                        renderer.refresh(agent);
                    }
                }
                default -> { /* user echoes: ignore */ }
            }
        });
    }

    private void onToolCall(BridgeMessage.ToolCall tool) {
        villagers.bySession(tool.sessionId()).ifPresent(agent -> {
            String label = firstNonBlank(tool.title(), tool.toolName(), tool.kind(), "tool");
            String status = tool.status() != null ? tool.status() : "";
            agent.setState(AgentVillager.State.WORKING);
            agent.setTool(label, tool.detail(), tool.status());
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "⚙ " + status));
            renderer.refresh(agent);

            Component line = prefix(agent).append(Component.text("⚙ " + label, NamedTextColor.YELLOW));
            if (tool.detail() != null && !tool.detail().equals(tool.title())) {
                line = line.append(Component.text(" " + tool.detail(), NamedTextColor.GRAY));
            }
            line = line.append(Component.text(" (" + status + ")", NamedTextColor.YELLOW));
            renderer.toConversers(agent, line);
        });
    }

    private void onPlan(BridgeMessage.Plan plan) {
        villagers.bySession(plan.sessionId()).ifPresent(agent -> {
            if (plan.entries() == null || plan.entries().isEmpty()) {
                return;
            }
            agent.setPlan(plan.entries());
            renderer.refresh(agent);

            Component out = prefix(agent).append(Component.text("Plan:", NamedTextColor.GOLD));
            for (BridgeMessage.PlanItem item : plan.entries()) {
                out = out.append(Component.newline())
                        .append(Component.text("  " + planIcon(item.status()) + " " + item.content(),
                                planColor(item.status())));
            }
            renderer.toConversers(agent, out);
        });
    }

    private void onPermissionRequest(BridgeMessage.PermissionRequest request) {
        villagers.bySession(request.sessionId()).ifPresent(agent -> {
            pendingPermissions.put(request.requestId(), request.sessionId());

            Component out = prefix(agent)
                    .append(Component.text("needs permission: ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(request.title() == null ? "a tool call" : request.title(),
                            NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("  "));
            for (BridgeMessage.PermissionOptionDto option : request.options()) {
                out = out.append(button(option, request.requestId())).append(Component.space());
            }
            renderer.toConversers(agent, out);
        });
    }

    private void onTurnEnd(BridgeMessage.TurnEnd end) {
        villagers.bySession(end.sessionId()).ifPresent(agent -> {
            agent.setBusy(false);
            agent.setState(AgentVillager.State.IDLE);
            agent.setTool(null, null, null);
            if (agent.hasTurnContent()) {
                String full = agent.takeTurn();
                agent.setLastLine(full);
                renderer.toConversers(agent, prefix(agent).append(Component.text(full, NamedTextColor.WHITE)));
            }
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "idle"));
            renderer.refresh(agent);
            if ("cancelled".equals(end.stopReason())) {
                renderer.toConversers(agent, prefix(agent).append(
                        Component.text("(cancelled)", NamedTextColor.GRAY)));
            }
        });
    }

    private void onError(BridgeMessage.AgentError error) {
        if (error.sessionId() == null) {
            plugin.getLogger().warning("Linker error: " + error.message());
            return;
        }
        villagers.bySession(error.sessionId()).ifPresent(agent -> {
            agent.setBusy(false);
            agent.setState(AgentVillager.State.ERROR);
            agent.setLastLine(error.message());
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "error"));
            renderer.refresh(agent);
            renderer.toConversers(agent, prefix(agent)
                    .append(Component.text("⚠ " + error.message(), NamedTextColor.RED)));
        });
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Component button(BridgeMessage.PermissionOptionDto option, String requestId) {
        NamedTextColor color = option.kind() != null && option.kind().startsWith("allow")
                ? NamedTextColor.GREEN : NamedTextColor.RED;
        return Component.text("[" + option.name() + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/agent perm " + requestId + " " + option.optionId()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to " + option.name())));
    }

    private Component prefix(AgentVillager agent) {
        return Component.text("[" + agent.agentName() + "] ", NamedTextColor.AQUA);
    }

    private static String planIcon(String status) {
        if (status == null) {
            return "•";
        }
        return switch (status) {
            case "completed" -> "✔";
            case "in_progress" -> "▶";
            default -> "•";
        };
    }

    private static NamedTextColor planColor(String status) {
        if (status == null) {
            return NamedTextColor.GRAY;
        }
        return switch (status) {
            case "completed" -> NamedTextColor.GREEN;
            case "in_progress" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GRAY;
        };
    }
}
