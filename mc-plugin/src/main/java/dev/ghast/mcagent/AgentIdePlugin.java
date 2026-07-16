package dev.ghast.mcagent;

import dev.ghast.bridge.BridgeMessage;
import dev.ghast.mcagent.command.AgentCommand;
import dev.ghast.mcagent.listener.ConversationListener;
import dev.ghast.mcagent.model.AgentVillager;
import dev.ghast.mcagent.render.DisplayRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point and service hub. Wires the WebSocket link to the linker, the villager and
 * conversation managers, the renderer and the inbound dispatcher, and exposes the high-level
 * actions ({@code spawn}, {@code prompt}, {@code cancel}, {@code remove}, permission answers) used
 * by the command and listeners.
 */
public final class AgentIdePlugin extends JavaPlugin {

    private LinkerConnection link;
    private VillagerManager villagers;
    private ConversationManager conversations;
    private DisplayRenderer renderer;
    private BridgeDispatcher dispatcher;

    private String configuredProfile;
    private String workspace;
    private double talkRadius;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var config = getConfig();

        NamespacedKey agentKey = new NamespacedKey(this, "agent-villager");
        NamespacedKey hologramKey = new NamespacedKey(this, "agent-hologram");

        this.configuredProfile = config.getString("agent.default-profile", "");
        this.workspace = config.getString("agent.workspace", "");
        this.talkRadius = config.getDouble("agent.talk-radius", 6.0);

        this.villagers = new VillagerManager(this, agentKey, hologramKey);
        this.conversations = new ConversationManager();
        this.renderer = new DisplayRenderer(this, hologramKey,
                config.getBoolean("display.hologram", true),
                config.getInt("display.hologram-max-chars", 300),
                config.getDouble("display.hologram-height", 2.4));
        this.dispatcher = new BridgeDispatcher(this, villagers, renderer,
                config.getBoolean("display.show-thoughts", false));

        int swept = villagers.sweepOrphans();
        if (swept > 0) {
            getLogger().info("Removed " + swept + " leftover agent entities from a previous run.");
        }

        String version = getPluginMeta().getVersion();
        this.link = new LinkerConnection(this,
                config.getString("linker.host", "127.0.0.1"),
                config.getInt("linker.port", 8765),
                config.getString("linker.token", ""),
                config.getLong("linker.reconnect-seconds", 5L),
                // Marshal every inbound message onto the main thread before touching Bukkit.
                message -> getServer().getScheduler().runTask(this, () -> dispatcher.handle(message)),
                () -> link.send(new BridgeMessage.Hello(version)),
                () -> getLogger().warning("Disconnected from linker; will retry."));

        getServer().getPluginManager().registerEvents(new ConversationListener(this), this);
        var command = new AgentCommand(this);
        getCommand("agent").setExecutor(command);
        getCommand("agent").setTabCompleter(command);

        link.connect();
        getLogger().info("MinecraftAgentIDE enabled. Linker: " + link.uri());
    }

    @Override
    public void onDisable() {
        if (link != null) {
            link.shutdown();
        }
        if (villagers != null) {
            villagers.removeAll();
        }
        if (conversations != null) {
            conversations.clear();
        }
    }

    // ---- Accessors used by command / listeners --------------------------------------------

    public VillagerManager villagers() {
        return villagers;
    }

    public ConversationManager conversations() {
        return conversations;
    }

    public BridgeDispatcher dispatcher() {
        return dispatcher;
    }

    public double talkRadius() {
        return talkRadius;
    }

    public boolean linkConnected() {
        return link != null && link.isConnected();
    }

    public java.net.URI linkUri() {
        return link.uri();
    }

    // ---- High-level actions ----------------------------------------------------------------

    /** Spawns a new agent villager in front of the player and asks the linker to start a session. */
    public void spawnAgent(Player player, String profileOverride) {
        if (!linkConnected()) {
            player.sendMessage(err("Not connected to the linker at " + link.uri()
                    + ". Start the linker desktop app first."));
            return;
        }
        String profile = firstNonBlank(profileOverride, configuredProfile);
        Location location = VillagerManager.inFrontOf(player);
        AgentVillager agent = villagers.spawn(location, profile);
        villagers.entityOf(agent).ifPresent(v -> renderer.setNamePlate(v, agent, "starting…"));
        villagers.entityOf(agent).ifPresent(v -> renderer.ensureHologram(agent, v));

        String cwd = workspace == null || workspace.isBlank() ? null : workspace;
        boolean sent = link.send(new BridgeMessage.CreateSession(
                agent.villagerId(), blankToNull(profile), cwd));
        if (!sent) {
            villagers.remove(agent);
            player.sendMessage(err("Could not reach the linker."));
            return;
        }
        conversations.start(player, agent);
        player.sendMessage(info("Spawned an agent villager and started a session. "
                + "Type in chat to talk; use ").append(cmd("/agent stop"))
                .append(info(" to leave.")));
    }

    /** Routes a player's chat line to their current agent as a prompt. */
    public void promptCurrent(Player player, String text) {
        conversations.current(player).ifPresentOrElse(agent -> {
            if (!agent.isReady()) {
                player.sendMessage(warn("The agent is still starting up — one moment…"));
                return;
            }
            player.sendMessage(Component.text("You → " + agent.agentName() + ": ", NamedTextColor.GRAY)
                    .append(Component.text(text, NamedTextColor.WHITE)));
            agent.setBusy(true);
            if (!link.send(new BridgeMessage.Prompt(agent.sessionId(), text))) {
                player.sendMessage(err("Lost connection to the linker."));
            }
        }, () -> player.sendMessage(warn("You're not talking to an agent. Use /agent talk near one.")));
    }

    public void cancelCurrent(Player player) {
        conversations.current(player).ifPresent(agent -> {
            if (agent.sessionId() != null) {
                link.send(new BridgeMessage.Cancel(agent.sessionId()));
            }
        });
    }

    public void removeAgent(Player player, AgentVillager agent) {
        if (agent.sessionId() != null) {
            link.send(new BridgeMessage.CloseSession(agent.sessionId()));
        }
        villagers.remove(agent);
        player.sendMessage(info("Removed the agent villager."));
    }

    public void answerPermission(Player player, String requestId, String optionId) {
        String sessionId = dispatcher.sessionForPermission(requestId);
        if (sessionId == null) {
            player.sendMessage(warn("That permission request is no longer pending."));
            return;
        }
        link.send(new BridgeMessage.PermissionResponse(sessionId, requestId, optionId));
        player.sendMessage(Component.text("Sent your decision to the agent.", NamedTextColor.GRAY));
    }

    // ---- Small component helpers -----------------------------------------------------------

    public static Component info(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    public static Component warn(String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    public static Component err(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    private static Component cmd(String text) {
        return Component.text(text, NamedTextColor.AQUA);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
