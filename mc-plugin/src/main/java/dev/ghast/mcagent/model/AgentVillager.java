package dev.ghast.mcagent.model;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Runtime state for one agent villager: which agent it runs, its ACP session, the players currently
 * talking to it, its hologram entity and the buffer of the in-progress assistant turn.
 */
public final class AgentVillager {

    private final UUID villagerEntityId;
    private final String profileId;

    private volatile String sessionId;
    private volatile String agentName;
    private volatile UUID hologramId;
    private volatile boolean busy;

    private final StringBuilder currentTurn = new StringBuilder();
    private final Set<UUID> conversing = new CopyOnWriteArraySet<>();

    public AgentVillager(UUID villagerEntityId, String profileId) {
        this.villagerEntityId = villagerEntityId;
        this.profileId = profileId;
    }

    public UUID villagerEntityId() {
        return villagerEntityId;
    }

    public String villagerId() {
        return villagerEntityId.toString();
    }

    public String profileId() {
        return profileId;
    }

    public String sessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isReady() {
        return sessionId != null;
    }

    public String agentName() {
        return agentName != null ? agentName : "Agent";
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public UUID hologramId() {
        return hologramId;
    }

    public void setHologramId(UUID hologramId) {
        this.hologramId = hologramId;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public Set<UUID> conversing() {
        return conversing;
    }

    public void addConversing(UUID player) {
        conversing.add(player);
    }

    public void removeConversing(UUID player) {
        conversing.remove(player);
    }

    public boolean isConversing(UUID player) {
        return conversing.contains(player);
    }

    // ---- Assistant turn buffering ----------------------------------------------------------

    public void appendTurn(String text) {
        currentTurn.append(text);
    }

    public String currentTurn() {
        return currentTurn.toString();
    }

    public String takeTurn() {
        String text = currentTurn.toString();
        currentTurn.setLength(0);
        return text;
    }

    public boolean hasTurnContent() {
        return currentTurn.length() > 0;
    }
}
