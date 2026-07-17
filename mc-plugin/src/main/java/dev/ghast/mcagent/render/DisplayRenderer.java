package dev.ghast.mcagent.render;

import dev.ghast.bridge.BridgeMessage;
import dev.ghast.mcagent.model.AgentVillager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders agent output in the world: a small, left-aligned floating {@link TextDisplay} panel above
 * the villager showing live status (state, current tool, plan, latest output), the villager's name
 * plate for at-a-glance state, and chat messages to conversing players. All methods must run on the
 * main server thread.
 */
public final class DisplayRenderer {

    /** Text scale for the panel — deliberately small so the multi-line panel stays compact. */
    private static final float TEXT_SCALE = 0.4f;
    /** Max plan rows shown before summarising the remainder. */
    private static final int MAX_PLAN_ROWS = 5;

    private final Plugin plugin;
    private final NamespacedKey hologramKey;
    private final boolean hologramEnabled;
    private final int maxChars;
    private final double height;
    private final boolean showThoughts;

    public DisplayRenderer(Plugin plugin, NamespacedKey hologramKey,
                           boolean hologramEnabled, int maxChars, double height, boolean showThoughts) {
        this.plugin = plugin;
        this.hologramKey = hologramKey;
        this.hologramEnabled = hologramEnabled;
        this.maxChars = Math.max(40, maxChars);
        this.height = height;
        this.showThoughts = showThoughts;
    }

    /** Spawns (or reuses) the panel for a villager. */
    public void ensureHologram(AgentVillager agent, Villager villager) {
        if (!hologramEnabled || agent.hologramId() != null) {
            return;
        }
        Location loc = villager.getLocation().clone().add(0, height, 0);
        TextDisplay display = villager.getWorld().spawn(loc, TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(false);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(org.bukkit.Color.fromARGB(190, 0, 0, 0));
            // Left-aligned, wider wrap so multi-line status reads like a small terminal panel.
            td.setAlignment(TextDisplay.TextAlignment.LEFT);
            td.setLineWidth(220);
            td.setTransformation(smallScale());
            td.setViewRange(1.0f);
            td.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
            td.text(Component.text(agent.agentName() + " is waking up…", NamedTextColor.GRAY));
        });
        agent.setHologramId(display.getUniqueId());
        refresh(agent);
    }

    private static Transformation smallScale() {
        return new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE),
                new AxisAngle4f(0, 0, 0, 1));
    }

    /** Rebuilds the whole status panel from the agent's live snapshot. */
    public void refresh(AgentVillager agent) {
        if (!hologramEnabled) {
            return;
        }
        TextDisplay display = displayOf(agent);
        if (display == null) {
            return;
        }
        display.text(buildPanel(agent));
    }

    /** Moves the panel back above the villager (used after a villager is teleported / repaired). */
    public void moveHologram(AgentVillager agent, Villager villager) {
        TextDisplay display = displayOf(agent);
        if (display == null) {
            return;
        }
        display.teleport(villager.getLocation().clone().add(0, height, 0));
    }

    private Component buildPanel(AgentVillager agent) {
        List<Component> lines = new ArrayList<>();

        // Header: name · profile
        Component header = Component.text("▸ ", NamedTextColor.AQUA)
                .append(Component.text(agent.agentName(), NamedTextColor.AQUA, TextDecoration.BOLD));
        if (agent.profileId() != null && !agent.profileId().isBlank()) {
            header = header.append(Component.text(" · " + agent.profileId(), NamedTextColor.DARK_AQUA));
        }
        lines.add(header);

        // State
        AgentVillager.State state = agent.state();
        lines.add(Component.text("● ", stateColor(state))
                .append(Component.text(state.label(), stateColor(state))));

        // Current tool
        if (agent.toolStatus() != null || agent.toolLabel() != null) {
            String label = agent.toolLabel() != null ? agent.toolLabel() : "tool";
            Component tool = Component.text("⚙ ", NamedTextColor.GOLD)
                    .append(Component.text(clip(label, 40), NamedTextColor.YELLOW));
            if (agent.toolDetail() != null && !agent.toolDetail().equals(agent.toolLabel())) {
                tool = tool.append(Component.text(" " + clip(agent.toolDetail(), 48), NamedTextColor.GRAY));
            }
            if (agent.toolStatus() != null) {
                tool = tool.append(Component.text(" (" + agent.toolStatus() + ")", toolStatusColor(agent.toolStatus())));
            }
            lines.add(tool);
        }

        // Plan
        List<BridgeMessage.PlanItem> plan = agent.plan();
        if (plan != null && !plan.isEmpty()) {
            lines.add(Component.text("Plan:", NamedTextColor.GOLD));
            int shown = Math.min(plan.size(), MAX_PLAN_ROWS);
            for (int i = 0; i < shown; i++) {
                BridgeMessage.PlanItem item = plan.get(i);
                lines.add(Component.text("  " + planIcon(item.status()) + " ", planColor(item.status()))
                        .append(Component.text(clip(item.content(), 40), planColor(item.status()))));
            }
            if (plan.size() > shown) {
                lines.add(Component.text("  …+" + (plan.size() - shown) + " more", NamedTextColor.DARK_GRAY));
            }
        }

        // Latest output
        if (agent.lastLine() != null && !agent.lastLine().isBlank()) {
            lines.add(Component.text("▪ ", NamedTextColor.WHITE)
                    .append(Component.text(tail(agent.lastLine(), maxChars), NamedTextColor.WHITE)));
        }

        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(lines.get(i));
        }
        return out;
    }

    public void removeHologram(AgentVillager agent) {
        UUID id = agent.hologramId();
        if (id == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(id);
        if (entity != null) {
            entity.remove();
        }
        agent.setHologramId(null);
    }

    /** Updates the villager's name plate to reflect the agent name and a short status suffix. */
    public void setNamePlate(Villager villager, AgentVillager agent, String status) {
        Component name = Component.text(agent.agentName(), NamedTextColor.AQUA, TextDecoration.BOLD);
        if (status != null && !status.isEmpty()) {
            name = name.append(Component.text(" · " + status, NamedTextColor.GRAY));
        }
        villager.customName(name);
        villager.setCustomNameVisible(true);
    }

    /** Sends a chat component to every player currently talking to the agent. */
    public void toConversers(AgentVillager agent, Component message) {
        for (UUID id : agent.conversing()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    public boolean showThoughts() {
        return showThoughts;
    }

    private TextDisplay displayOf(AgentVillager agent) {
        if (agent.hologramId() == null) {
            return null;
        }
        Entity entity = plugin.getServer().getEntity(agent.hologramId());
        return entity instanceof TextDisplay display ? display : null;
    }

    private static TextColor stateColor(AgentVillager.State state) {
        return switch (state) {
            case READY, IDLE -> NamedTextColor.GREEN;
            case THINKING, WORKING, STARTING -> NamedTextColor.YELLOW;
            case ERROR -> NamedTextColor.RED;
            case OFFLINE -> NamedTextColor.DARK_GRAY;
        };
    }

    private static TextColor toolStatusColor(String status) {
        if (status == null) {
            return NamedTextColor.GRAY;
        }
        return switch (status) {
            case "completed" -> NamedTextColor.GREEN;
            case "failed" -> NamedTextColor.RED;
            case "in_progress" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GRAY;
        };
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

    private static TextColor planColor(String status) {
        if (status == null) {
            return NamedTextColor.GRAY;
        }
        return switch (status) {
            case "completed" -> NamedTextColor.GREEN;
            case "in_progress" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GRAY;
        };
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= max ? single : single.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String tail(String text, int max) {
        String single = text.replace('\n', ' ').trim();
        if (single.length() <= max) {
            return single;
        }
        return "…" + single.substring(single.length() - max);
    }
}
