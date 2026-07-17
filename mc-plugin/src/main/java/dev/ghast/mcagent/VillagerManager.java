package dev.ghast.mcagent;

import dev.ghast.mcagent.model.AgentVillager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    // Metadata persisted on the villager entity so its agent binding survives a server restart.
    private final NamespacedKey profileKey;
    private final NamespacedKey sessionKey;
    private final NamespacedKey nameKey;

    private final Map<String, AgentVillager> byVillagerId = new ConcurrentHashMap<>();
    private final Map<String, AgentVillager> bySessionId = new ConcurrentHashMap<>();

    public VillagerManager(Plugin plugin, NamespacedKey agentKey, NamespacedKey hologramKey) {
        this.plugin = plugin;
        this.agentKey = agentKey;
        this.hologramKey = hologramKey;
        this.profileKey = new NamespacedKey(plugin, "agent-profile");
        this.sessionKey = new NamespacedKey(plugin, "agent-session");
        this.nameKey = new NamespacedKey(plugin, "agent-name");
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
            PersistentDataContainer pdc = v.getPersistentDataContainer();
            pdc.set(agentKey, PersistentDataType.BYTE, (byte) 1);
            if (profileId != null) {
                pdc.set(profileKey, PersistentDataType.STRING, profileId);
            }
        });

        AgentVillager agent = new AgentVillager(villager.getUniqueId(), profileId);
        byVillagerId.put(agent.villagerId(), agent);
        return agent;
    }

    /** Writes the agent's session binding onto the villager entity so it survives a restart. */
    public void persistMeta(AgentVillager agent) {
        entityOf(agent).ifPresent(v -> {
            PersistentDataContainer pdc = v.getPersistentDataContainer();
            setOrClear(pdc, profileKey, agent.profileId());
            setOrClear(pdc, sessionKey, agent.sessionId());
            setOrClear(pdc, nameKey, agent.agentNameRaw());
        });
    }

    private static void setOrClear(PersistentDataContainer pdc, NamespacedKey key, String value) {
        if (value == null || value.isBlank()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, value);
        }
    }

    /**
     * Rebuilds runtime state for agent villagers left in loaded worlds by a previous run and marks
     * them for session reattachment. Returns the restored agents.
     */
    public java.util.List<AgentVillager> restoreFromWorld() {
        java.util.List<AgentVillager> restored = new java.util.ArrayList<>();
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getType() != EntityType.VILLAGER
                        || !entity.getPersistentDataContainer().has(agentKey, PersistentDataType.BYTE)
                        || byVillagerId.containsKey(entity.getUniqueId().toString())) {
                    continue;
                }
                PersistentDataContainer pdc = entity.getPersistentDataContainer();
                String profile = pdc.get(profileKey, PersistentDataType.STRING);
                AgentVillager agent = new AgentVillager(entity.getUniqueId(), profile);
                String priorSession = pdc.get(sessionKey, PersistentDataType.STRING);
                String priorName = pdc.get(nameKey, PersistentDataType.STRING);
                if (priorSession != null) {
                    agent.setSessionId(priorSession);
                }
                if (priorName != null) {
                    agent.setAgentName(priorName);
                }
                agent.setState(AgentVillager.State.OFFLINE);
                agent.setNeedsResume(true);
                byVillagerId.put(agent.villagerId(), agent);
                restored.add(agent);
            }
        }
        return restored;
    }

    /** Teleports the villager (and its hologram, if any) to {@code location}. */
    public boolean moveTo(AgentVillager agent, Location location) {
        Optional<Villager> villager = entityOf(agent);
        if (villager.isEmpty()) {
            return false;
        }
        villager.get().teleport(location);
        UUID hologram = agent.hologramId();
        if (hologram != null) {
            Entity entity = plugin.getServer().getEntity(hologram);
            if (entity != null) {
                entity.teleport(location.clone().add(0, 2.4, 0));
            }
        }
        return true;
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
        // Holograms currently owned by a tracked agent must survive the sweep.
        Set<UUID> ownedHolograms = new HashSet<>();
        for (AgentVillager agent : byVillagerId.values()) {
            if (agent.hologramId() != null) {
                ownedHolograms.add(agent.hologramId());
            }
        }
        int removed = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                PersistentDataContainer pdc = entity.getPersistentDataContainer();
                boolean isAgent = entity.getType() == EntityType.VILLAGER
                        && pdc.has(agentKey, PersistentDataType.BYTE);
                boolean isHologram = pdc.has(hologramKey, PersistentDataType.BYTE);
                boolean orphanAgent = isAgent && !byVillagerId.containsKey(entity.getUniqueId().toString());
                boolean orphanHologram = isHologram && !ownedHolograms.contains(entity.getUniqueId());
                if (orphanAgent || orphanHologram) {
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
