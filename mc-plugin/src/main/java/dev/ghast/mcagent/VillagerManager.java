package dev.ghast.mcagent;

import dev.ghast.mcagent.model.AgentVillager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and tracks agent villagers. Each villager is a static, invulnerable {@link Villager}
 * tagged in its persistent data so orphans left by a crash can be swept on startup. Runtime state
 * lives in {@link AgentVillager}, indexed by villager id and (once established) ACP session id.
 */
public final class VillagerManager {

    private final Plugin plugin;
    private final NamespacedKey agentKey;
    private final NamespacedKey hologramKey;

    private final Map<String, AgentVillager> byVillagerId = new ConcurrentHashMap<>();
    private final Map<String, AgentVillager> bySessionId = new ConcurrentHashMap<>();

    public VillagerManager(Plugin plugin, NamespacedKey agentKey, NamespacedKey hologramKey) {
        this.plugin = plugin;
        this.agentKey = agentKey;
        this.hologramKey = hologramKey;
    }

    public NamespacedKey agentKey() {
        return agentKey;
    }

    /** Spawns a new agent villager at {@code location} bound to {@code profileId}. */
    public AgentVillager spawn(Location location, String profileId) {
        Villager villager = location.getWorld().spawn(location, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.getPersistentDataContainer().set(agentKey, PersistentDataType.BYTE, (byte) 1);
        });

        AgentVillager agent = new AgentVillager(villager.getUniqueId(), profileId);
        byVillagerId.put(agent.villagerId(), agent);
        return agent;
    }

    public void indexSession(AgentVillager agent) {
        if (agent.sessionId() != null) {
            bySessionId.put(agent.sessionId(), agent);
        }
    }

    public Optional<AgentVillager> byVillagerId(String villagerId) {
        return Optional.ofNullable(byVillagerId.get(villagerId));
    }

    public Optional<AgentVillager> bySession(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(bySessionId.get(sessionId));
    }

    public Optional<AgentVillager> byEntity(UUID entityId) {
        return Optional.ofNullable(byVillagerId.get(entityId.toString()));
    }

    public boolean isAgentVillager(Entity entity) {
        return entity.getPersistentDataContainer().has(agentKey, PersistentDataType.BYTE);
    }

    public java.util.Collection<AgentVillager> all() {
        return byVillagerId.values();
    }

    public Optional<Villager> entityOf(AgentVillager agent) {
        Entity entity = plugin.getServer().getEntity(agent.villagerEntityId());
        return entity instanceof Villager villager ? Optional.of(villager) : Optional.empty();
    }

    /** Finds the nearest agent villager to a player within {@code radius} blocks. */
    public Optional<AgentVillager> nearest(Player player, double radius) {
        AgentVillager best = null;
        double bestDist = radius * radius;
        Location origin = player.getLocation();
        for (AgentVillager agent : byVillagerId.values()) {
            Optional<Villager> villager = entityOf(agent);
            if (villager.isEmpty() || !villager.get().getWorld().equals(origin.getWorld())) {
                continue;
            }
            double dist = villager.get().getLocation().distanceSquared(origin);
            if (dist <= bestDist) {
                bestDist = dist;
                best = agent;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Removes an agent villager, its hologram entity and its indexes. */
    public void remove(AgentVillager agent) {
        byVillagerId.remove(agent.villagerId());
        if (agent.sessionId() != null) {
            bySessionId.remove(agent.sessionId());
        }
        entityOf(agent).ifPresent(Entity::remove);
        UUID hologram = agent.hologramId();
        if (hologram != null) {
            Entity entity = plugin.getServer().getEntity(hologram);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /** Removes every tracked villager (used on plugin disable). */
    public void removeAll() {
        for (AgentVillager agent : byVillagerId.values()) {
            entityOf(agent).ifPresent(Entity::remove);
            UUID hologram = agent.hologramId();
            if (hologram != null) {
                Entity entity = plugin.getServer().getEntity(hologram);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        byVillagerId.clear();
        bySessionId.clear();
    }

    /**
     * Removes any agent villagers / holograms left in loaded worlds from a previous run. Runtime
     * state does not survive a restart, so these entities would otherwise be inert.
     */
    public int sweepOrphans() {
        int removed = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                boolean isAgent = entity.getType() == EntityType.VILLAGER
                        && entity.getPersistentDataContainer().has(agentKey, PersistentDataType.BYTE);
                boolean isHologram = entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE);
                if ((isAgent || isHologram) && !byVillagerId.containsKey(entity.getUniqueId().toString())) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    static Location inFrontOf(Player player) {
        Location loc = player.getLocation();
        Vector direction = loc.getDirection().setY(0).normalize().multiply(1.5);
        Location target = loc.clone().add(direction);
        target.setYaw(loc.getYaw() + 180f);
        target.setPitch(0);
        return target;
    }
}
