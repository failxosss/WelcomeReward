package cz.tvujserver.welcomereward;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WelcomeRewardPlugin extends JavaPlugin implements Listener {

    private final List<ActiveSession> activeSessions = Collections.synchronizedList(new ArrayList<>());

    private Set<String> greetings;
    private List<String> rewardCommands;
    private int windowSeconds;
    private String msgRewarded;
    private String msgNewPlayer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("WelcomeReward enabled - greeting window: " + windowSeconds + "s.");
    }

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();

        windowSeconds = cfg.getInt("window-seconds", 30);

        greetings = new HashSet<>();
        for (String g : cfg.getStringList("greetings")) {
            greetings.add(g.toLowerCase(Locale.ROOT));
        }

        rewardCommands = cfg.getStringList("reward-commands");

        msgRewarded = colorize(cfg.getString("messages.rewarded",
                "&aThanks for greeting the new player! You received a reward."));
        msgNewPlayer = colorize(cfg.getString("messages.new-player-join",
                "&e%player% is on the server for the first time! Greet them within &6%seconds% seconds &eto get a reward."));
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Only react to players who have never been on the server before
        if (player.hasPlayedBefore()) {
            return;
        }

        ActiveSession session = new ActiveSession(player.getUniqueId());
        activeSessions.add(session);

        String announce = msgNewPlayer
                .replace("%player%", player.getName())
                .replace("%seconds%", String.valueOf(windowSeconds));
        Bukkit.broadcastMessage(announce);

        // Remove the session once the time window has passed - no one can be rewarded for this player after that
        Bukkit.getScheduler().runTaskLater(this, () -> activeSessions.remove(session), windowSeconds * 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (activeSessions.isEmpty()) {
            return;
        }

        Player sender = event.getPlayer();
        String normalized = ChatColor.stripColor(event.getMessage()).trim().toLowerCase(Locale.ROOT);

        if (!isGreeting(normalized)) {
            return;
        }

        // Work on a copy of the list to avoid threading issues (chat event is async)
        List<ActiveSession> snapshot;
        synchronized (activeSessions) {
            snapshot = new ArrayList<>(activeSessions);
        }

        for (ActiveSession session : snapshot) {
            if (session.newPlayerUuid.equals(sender.getUniqueId())) {
                continue; // the new player can't reward themselves
            }
            if (!session.rewarded.add(sender.getUniqueId())) {
                continue; // this player was already rewarded for this specific session
            }
            reward(sender);
        }
    }

    private boolean isGreeting(String normalized) {
        if (normalized.isEmpty()) {
            return false;
        }
        if (greetings.contains(normalized)) {
            return true;
        }
        for (String word : normalized.split("\\s+")) {
            if (greetings.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void reward(Player player) {
        // Console commands must run on the main server thread
        Bukkit.getScheduler().runTask(this, () -> {
            for (String cmd : rewardCommands) {
                String finalCmd = cmd.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
            player.sendMessage(msgRewarded);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfigValues();
            sender.sendMessage(colorize("&aWelcomeReward: config reloaded."));
            return true;
        }
        sender.sendMessage(colorize("&eUsage: /welcomereward reload"));
        return true;
    }

    private static class ActiveSession {
        final UUID newPlayerUuid;
        final Set<UUID> rewarded = ConcurrentHashMap.newKeySet();

        ActiveSession(UUID newPlayerUuid) {
            this.newPlayerUuid = newPlayerUuid;
        }
    }
}
