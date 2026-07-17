package dev.ghast.mcagent.model;

import dev.ghast.bridge.BridgeMessage;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Runtime state for one agent villager: which agent it runs, its ACP session, the players currently
 * talking to it, its hologram entity, a live status snapshot (state, current tool, plan, latest
 * output) for the hologram panel, and the buffer of the in-progress assistant turn.
 */
public final class AgentVillager {

    /** Coarse lifecycle state shown on the hologram / name plate. */
    public enum State {
        STARTING("starting"), READY("ready"), THINKING("thinking"), WORKING("working"),
        IDLE("idle"), ERROR("error"), OFFLINE("offline");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final UUID villagerEntityId;
    private final String profileId;

    private volatile String sessionId;
    private volatile String agentName;
    private volatile UUID hologramId;
    private volatile boolean busy;

    // ---- Live status snapshot (for the hologram panel) --------------------------------------
    private volatile State state = State.STARTING;
    private volatile String toolLabel;     // e.g. "Read" or "Read /path"
    private volatile String toolDetail;    // e.g. a command or file path
    private volatile String toolStatus;    // pending / in_progress / completed / failed
    private volatile List<BridgeMessage.PlanItem> plan = List.of();
    private volatile String lastLine = "";  // most recent assistant text (trimmed for display)
    private volatile boolean needsResume;   // restored from disk, waiting to reattach its session

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

    /** The agent name as set by the linker, or {@code null} if not yet known (unlike {@link #agentName()}). */
    public String agentNameRaw() {
        return agentName;
    }

    // ---- Live status snapshot --------------------------------------------------------------

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String toolLabel() {
        return toolLabel;
    }

    public String toolDetail() {
        return toolDetail;
    }

    public String toolStatus() {
        return toolStatus;
    }

    public void setTool(String label, String detail, String status) {
        this.toolLabel = label;
        this.toolDetail = detail;
        this.toolStatus = status;
    }

    public List<BridgeMessage.PlanItem> plan() {
        return plan;
    }

    public void setPlan(List<BridgeMessage.PlanItem> plan) {
        this.plan = plan == null ? List.of() : plan;
    }

    public String lastLine() {
        return lastLine;
    }

    public void setLastLine(String lastLine) {
        this.lastLine = lastLine == null ? "" : lastLine;
    }

    public boolean needsResume() {
        return needsResume;
    }

    public void setNeedsResume(boolean needsResume) {
        this.needsResume = needsResume;
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
