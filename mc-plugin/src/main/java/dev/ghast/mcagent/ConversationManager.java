package dev.ghast.mcagent;

import dev.ghast.mcagent.model.AgentVillager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which villager each player is currently talking to. While in a conversation a player's
 * chat is captured and routed to that villager's agent instead of being broadcast.
 */
public final class ConversationManager {

    private final Map<UUID, AgentVillager> byPlayer = new ConcurrentHashMap<>();

    public void start(Player player, AgentVillager agent) {
        stop(player);
        byPlayer.put(player.getUniqueId(), agent);
        agent.addConversing(player.getUniqueId());
    }

    public void stop(Player player) {
        AgentVillager previous = byPlayer.remove(player.getUniqueId());
        if (previous != null) {
            previous.removeConversing(player.getUniqueId());
        }
    }

    public Optional<AgentVillager> current(Player player) {
        return Optional.ofNullable(byPlayer.get(player.getUniqueId()));
    }

    public boolean isTalking(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    public void clear() {
        byPlayer.clear();
    }
}
