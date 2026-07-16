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
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies inbound {@link BridgeMessage}s from the linker to the game world. Every method here runs
 * on the main server thread (the caller marshals from the WebSocket thread).
 */
public final class BridgeDispatcher {

    private final Plugin plugin;
    private final VillagerManager villagers;
    private final DisplayRenderer renderer;
    private final boolean showThoughts;

    private volatile List<BridgeMessage.AgentProfileInfo> profiles = List.of();
    private volatile String defaultProfile = "";
    // requestId -> sessionId, so the /agent perm command knows which session to answer.
    private final Map<String, String> pendingPermissions = new ConcurrentHashMap<>();

    public BridgeDispatcher(Plugin plugin, VillagerManager villagers,
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
    }

    private void onSessionCreated(BridgeMessage.SessionCreated created) {
        Optional<AgentVillager> found = villagers.byVillagerId(created.villagerId());
        if (found.isEmpty()) {
            return;
        }
        AgentVillager agent = found.get();
        agent.setSessionId(created.sessionId());
        agent.setAgentName(created.agentName());
        villagers.indexSession(agent);

        villagers.entityOf(agent).ifPresent(v -> {
            renderer.ensureHologram(agent, v);
            renderer.setNamePlate(v, agent, "ready");
            renderer.updateHologram(agent, "Ready. Talk to me!", NamedTextColor.GREEN);
        });
        renderer.toConversers(agent, prefix(agent)
                .append(Component.text("I'm ready — just type to chat with me.", NamedTextColor.GREEN)));
    }

    private void onMessage(BridgeMessage.Message msg) {
        villagers.bySession(msg.sessionId()).ifPresent(agent -> {
            switch (msg.role()) {
                case "assistant" -> {
                    agent.appendTurn(msg.text());
                    renderer.updateHologram(agent, agent.currentTurn(), NamedTextColor.WHITE);
                }
                case "thought" -> {
                    if (showThoughts) {
                        renderer.updateHologram(agent, msg.text(), NamedTextColor.GRAY);
                    }
                }
                default -> { /* user echoes: ignore */ }
            }
        });
    }

    private void onToolCall(BridgeMessage.ToolCall tool) {
        villagers.bySession(tool.sessionId()).ifPresent(agent -> {
            String title = tool.title() != null ? tool.title() : tool.kind();
            String status = tool.status() != null ? tool.status() : "";
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "⚙ " + status));
            renderer.toConversers(agent, prefix(agent)
                    .append(Component.text("⚙ " + title + " (" + status + ")", NamedTextColor.YELLOW)));
        });
    }

    private void onPlan(BridgeMessage.Plan plan) {
        villagers.bySession(plan.sessionId()).ifPresent(agent -> {
            if (plan.entries() == null || plan.entries().isEmpty()) {
                return;
            }
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
            if (agent.hasTurnContent()) {
                String full = agent.takeTurn();
                renderer.toConversers(agent, prefix(agent).append(Component.text(full, NamedTextColor.WHITE)));
            }
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "idle"));
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
            villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "error"));
            renderer.toConversers(agent, prefix(agent)
                    .append(Component.text("⚠ " + error.message(), NamedTextColor.RED)));
        });
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
