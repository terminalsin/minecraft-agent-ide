package dev.ghast.mcagent.command;

import dev.ghast.mcagent.AgentIdePlugin;
import dev.ghast.mcagent.model.AgentVillager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Implements {@code /agent <subcommand>}. */
public final class AgentCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("spawn", "talk", "stop", "list", "remove", "status", "debug", "help");

    /** Actions under {@code /agent debug <action>}. */
    private static final List<String> DEBUG_ACTIONS =
            List.of("list", "here", "move", "remove", "removeall", "rehologram", "resume", "sweep", "info", "help");

    /** Wide radius for debug targeting so even far/broken villagers can be grabbed. */
    private static final double DEBUG_RADIUS = 128.0;

    private final AgentIdePlugin plugin;

    public AgentCommand(AgentIdePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        // 'perm' is used by clickable chat buttons and needs no player-only guard beyond being a player.
        if (sub.equals("perm")) {
            handlePerm(sender, args);
            return true;
        }
        if (sub.equals("status")) {
            handleStatus(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "spawn" -> plugin.spawnAgent(player, args.length > 1 ? args[1] : null);
            case "talk" -> handleTalk(player);
            case "stop" -> handleStop(player);
            case "list" -> handleList(player);
            case "remove" -> handleRemove(player);
            case "debug" -> handleDebug(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleDebug(Player player, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "list" -> debugList(player);
            case "here", "move", "come" -> debugMoveHere(player);
            case "remove" -> debugRemoveNearest(player);
            case "removeall" -> debugRemoveAll(player);
            case "rehologram" -> debugRehologram(player);
            case "resume" -> debugResume(player);
            case "sweep" -> debugSweep(player);
            case "info" -> debugInfo(player);
            default -> debugHelp(player);
        }
    }

    private void debugList(Player player) {
        var agents = plugin.villagers().all();
        if (agents.isEmpty()) {
            player.sendMessage(AgentIdePlugin.warn("No agent villagers tracked."));
            return;
        }
        player.sendMessage(Component.text("Agent villagers (" + agents.size() + "):", NamedTextColor.GOLD));
        for (AgentVillager agent : agents) {
            var entity = plugin.villagers().entityOf(agent);
            String where = entity.map(v -> {
                var l = v.getLocation();
                return String.format("%s %d,%d,%d", l.getWorld().getName(),
                        l.getBlockX(), l.getBlockY(), l.getBlockZ());
            }).orElse("entity MISSING");
            boolean holo = agent.hologramId() != null
                    && plugin.getServer().getEntity(agent.hologramId()) != null;
            player.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(agent.agentName(), NamedTextColor.AQUA))
                    .append(Component.text(" [" + agent.profileId() + "] " + agent.state().label()
                            + " · session " + shortId(agent.sessionId())
                            + " · holo " + (holo ? "ok" : "MISSING")
                            + " · " + where, NamedTextColor.GRAY)));
        }
    }

    private void debugMoveHere(Player player) {
        withNearest(player, agent -> {
            if (plugin.villagers().moveTo(agent, player.getLocation())) {
                player.sendMessage(AgentIdePlugin.info("Moved " + agent.agentName() + " to you."));
            } else {
                player.sendMessage(AgentIdePlugin.err("That villager's entity is missing; try /agent debug rehologram or removeall."));
            }
        });
    }

    private void debugRemoveNearest(Player player) {
        withNearest(player, agent -> {
            plugin.conversations().stop(player);
            plugin.removeAgent(player, agent);
        });
    }

    private void debugRemoveAll(Player player) {
        var agents = List.copyOf(plugin.villagers().all());
        for (AgentVillager agent : agents) {
            plugin.removeAgent(player, agent);
        }
        int swept = plugin.villagers().sweepOrphans();
        player.sendMessage(AgentIdePlugin.info("Removed " + agents.size()
                + " villager(s) and swept " + swept + " orphan entities."));
    }

    private void debugRehologram(Player player) {
        withNearest(player, agent -> {
            plugin.renderer().removeHologram(agent);
            plugin.villagers().entityOf(agent).ifPresentOrElse(v -> {
                plugin.renderer().ensureHologram(agent, v);
                plugin.renderer().moveHologram(agent, v);
                plugin.renderer().refresh(agent);
                player.sendMessage(AgentIdePlugin.info("Rebuilt the panel for " + agent.agentName() + "."));
            }, () -> player.sendMessage(AgentIdePlugin.err("Villager entity missing; use /agent debug removeall.")));
        });
    }

    private void debugResume(Player player) {
        withNearest(player, agent -> {
            if (plugin.resumeAgent(agent)) {
                player.sendMessage(AgentIdePlugin.info("Requested resume of " + agent.agentName() + "'s session."));
            } else {
                player.sendMessage(AgentIdePlugin.err("Not connected to the linker."));
            }
        });
    }

    private void debugSweep(Player player) {
        int swept = plugin.villagers().sweepOrphans();
        player.sendMessage(AgentIdePlugin.info("Swept " + swept + " orphan agent/hologram entities."));
    }

    private void debugInfo(Player player) {
        withNearest(player, agent -> {
            player.sendMessage(Component.text("Agent debug info:", NamedTextColor.GOLD));
            info(player, "name", agent.agentName());
            info(player, "profile", String.valueOf(agent.profileId()));
            info(player, "villagerId", agent.villagerId());
            info(player, "sessionId", String.valueOf(agent.sessionId()));
            info(player, "state", agent.state().label());
            info(player, "needsResume", String.valueOf(agent.needsResume()));
            info(player, "hologram", agent.hologramId() != null
                    && plugin.getServer().getEntity(agent.hologramId()) != null ? "ok" : "missing");
            info(player, "entity", plugin.villagers().entityOf(agent).isPresent() ? "present" : "MISSING");
        });
    }

    private void debugHelp(Player player) {
        player.sendMessage(Component.text("Agent debug tools", NamedTextColor.AQUA));
        line(player, "/agent debug list", "list every tracked villager with state, session, holo, position");
        line(player, "/agent debug here", "teleport the nearest agent villager to you");
        line(player, "/agent debug move", "alias of here — bring a stuck villager to you");
        line(player, "/agent debug remove", "force-remove the nearest agent villager");
        line(player, "/agent debug removeall", "force-remove every villager and sweep orphans");
        line(player, "/agent debug rehologram", "rebuild the floating panel if it's broken/missing");
        line(player, "/agent debug resume", "reconnect the nearest villager's agent session");
        line(player, "/agent debug sweep", "remove leftover/orphaned agent + hologram entities");
        line(player, "/agent debug info", "dump the nearest villager's internal state");
    }

    private void withNearest(Player player, java.util.function.Consumer<AgentVillager> action) {
        plugin.villagers().nearest(player, DEBUG_RADIUS).ifPresentOrElse(action,
                () -> player.sendMessage(AgentIdePlugin.warn(
                        "No agent villager within " + (int) DEBUG_RADIUS + " blocks.")));
    }

    private void info(Player player, String key, String value) {
        player.sendMessage(Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private static String shortId(String id) {
        if (id == null) {
            return "none";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private void handleTalk(Player player) {
        plugin.villagers().nearest(player, plugin.talkRadius()).ifPresentOrElse(agent -> {
            plugin.conversations().start(player, agent);
            player.sendMessage(AgentIdePlugin.info("Now talking to " + agent.agentName()
                    + ". Type in chat; /agent stop to leave."));
        }, () -> player.sendMessage(AgentIdePlugin.warn(
                "No agent villager nearby. Use /agent spawn first.")));
    }

    private void handleStop(Player player) {
        if (!plugin.conversations().isTalking(player)) {
            player.sendMessage(AgentIdePlugin.warn("You're not talking to an agent."));
            return;
        }
        plugin.cancelCurrent(player);
        plugin.conversations().stop(player);
        player.sendMessage(AgentIdePlugin.info("Left the conversation."));
    }

    private void handleList(Player player) {
        var agents = plugin.villagers().all();
        if (agents.isEmpty()) {
            player.sendMessage(AgentIdePlugin.warn("No agent villagers exist. Use /agent spawn."));
            return;
        }
        player.sendMessage(Component.text("Agent villagers:", NamedTextColor.GOLD));
        for (AgentVillager agent : agents) {
            String state = agent.isReady() ? (agent.isBusy() ? "busy" : "ready") : "starting";
            player.sendMessage(Component.text("  • " + agent.agentName() + " [" + agent.profileId() + "] — "
                    + state, NamedTextColor.GRAY));
        }
    }

    private void handleRemove(Player player) {
        plugin.villagers().nearest(player, plugin.talkRadius()).ifPresentOrElse(
                agent -> {
                    plugin.conversations().stop(player);
                    plugin.removeAgent(player, agent);
                },
                () -> player.sendMessage(AgentIdePlugin.warn("No agent villager nearby.")));
    }

    private void handleStatus(CommandSender sender) {
        if (plugin.linkConnected()) {
            sender.sendMessage(AgentIdePlugin.info("Connected to linker at " + plugin.linkUri()));
            var profiles = plugin.dispatcher().profiles();
            if (!profiles.isEmpty()) {
                sender.sendMessage(Component.text("Available agents: "
                        + profiles.stream().map(p -> p.id()).toList(), NamedTextColor.GRAY));
            }
        } else {
            sender.sendMessage(AgentIdePlugin.err("Not connected to the linker at " + plugin.linkUri()
                    + ". Is the desktop linker running?"));
        }
    }

    private void handlePerm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return;
        }
        if (args.length < 3) {
            player.sendMessage(AgentIdePlugin.warn("Usage: /agent perm <requestId> <optionId>"));
            return;
        }
        plugin.answerPermission(player, args[1], args[2]);
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("Minecraft Agent IDE", NamedTextColor.AQUA));
        line(player, "/agent spawn [profile]", "spawn an agent villager and start talking");
        line(player, "/agent talk", "talk to the nearest agent villager");
        line(player, "/agent stop", "leave the conversation / cancel the turn");
        line(player, "/agent list", "list active agent villagers");
        line(player, "/agent remove", "remove the nearest agent villager");
        line(player, "/agent status", "show linker connection status");
        line(player, "/agent debug", "diagnostics: move/remove/rehologram/resume broken villagers");
    }

    private void line(Player player, String cmd, String desc) {
        player.sendMessage(Component.text("  " + cmd, NamedTextColor.YELLOW)
                .append(Component.text(" — " + desc, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            List<String> ids = new ArrayList<>();
            plugin.dispatcher().profiles().forEach(p -> ids.add(p.id()));
            return filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(DEBUG_ACTIONS, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
