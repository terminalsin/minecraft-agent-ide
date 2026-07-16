package dev.ghast.mcagent.listener;

import dev.ghast.mcagent.AgentIdePlugin;
import dev.ghast.mcagent.model.AgentVillager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * Turns villager interactions into conversations and captures the chat of players who are talking
 * to an agent, routing it to the linker instead of broadcasting it.
 */
public final class ConversationListener implements Listener {

    private final AgentIdePlugin plugin;

    public ConversationListener(AgentIdePlugin plugin) {
        this.plugin = plugin;
    }

    /** Right-clicking an agent villager starts (or focuses) a conversation instead of trading. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!plugin.villagers().isAgentVillager(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Optional<AgentVillager> agent = plugin.villagers().byEntity(event.getRightClicked().getUniqueId());
        agent.ifPresent(a -> {
            plugin.conversations().start(player, a);
            player.sendMessage(AgentIdePlugin.info("Now talking to " + a.agentName()
                    + ". Type in chat; /agent stop to leave."));
        });
    }

    /** Agent villagers must never open a trade menu, even via plugins that force it. */
    @EventHandler(ignoreCancelled = true)
    public void onTradeOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.MERCHANT
                && event.getInventory().getHolder() instanceof org.bukkit.entity.Villager villager
                && plugin.villagers().isAgentVillager(villager)) {
            event.setCancelled(true);
        }
    }

    /** Protect agent villagers from being killed. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.villagers().isAgentVillager(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Capture chat from players in a conversation and forward it as a prompt. */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.conversations().isTalking(player)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.isEmpty()) {
            return;
        }
        // Hop back onto the main thread — chat events fire asynchronously.
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.promptCurrent(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.conversations().stop(event.getPlayer());
    }
}
