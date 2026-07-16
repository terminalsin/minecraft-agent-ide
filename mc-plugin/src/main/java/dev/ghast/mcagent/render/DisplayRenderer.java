package dev.ghast.mcagent.render;

import dev.ghast.mcagent.model.AgentVillager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Renders agent output in the world: a floating {@link TextDisplay} hologram above the villager for
 * live streaming, the villager's name plate for status, and chat messages to conversing players.
 * All methods must be called on the main server thread.
 */
public final class DisplayRenderer {

    private final Plugin plugin;
    private final NamespacedKey hologramKey;
    private final boolean hologramEnabled;
    private final int maxChars;
    private final double height;

    public DisplayRenderer(Plugin plugin, NamespacedKey hologramKey,
                           boolean hologramEnabled, int maxChars, double height) {
        this.plugin = plugin;
        this.hologramKey = hologramKey;
        this.hologramEnabled = hologramEnabled;
        this.maxChars = Math.max(40, maxChars);
        this.height = height;
    }

    /** Spawns (or reuses) the hologram for a villager and returns its entity id. */
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
            td.setBackgroundColor(org.bukkit.Color.fromARGB(160, 0, 0, 0));
            td.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(0.9f, 0.9f, 0.9f),
                    new AxisAngle4f(0, 0, 0, 1)));
            td.setViewRange(0.6f);
            td.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
            td.text(Component.text(agent.agentName() + " is waking up…", NamedTextColor.GRAY));
        });
        agent.setHologramId(display.getUniqueId());
    }

    public void updateHologram(AgentVillager agent, String body, NamedTextColor color) {
        if (!hologramEnabled || agent.hologramId() == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(agent.hologramId());
        if (!(entity instanceof TextDisplay display)) {
            return;
        }
        String text = tail(body, maxChars);
        Component component = Component.text(agent.agentName(), NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text(text.isEmpty() ? "…" : text, color));
        display.text(component);
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

    private static String tail(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return "…" + text.substring(text.length() - max);
    }
}
