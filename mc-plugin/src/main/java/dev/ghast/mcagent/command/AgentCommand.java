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
            List.of("spawn", "talk", "stop", "list", "remove", "status", "help");

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
            default -> sendHelp(player);
        }
        return true;
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
