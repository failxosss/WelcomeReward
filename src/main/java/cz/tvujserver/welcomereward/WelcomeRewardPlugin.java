package cz.tvujserver.welcomereward;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WelcomeRewardPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final List<ActiveSession> activeSessions = Collections.synchronizedList(new ArrayList<>());

    private List<String> rewardCommands;
    private int windowSeconds;
    private String msgRewarded;
    private String msgNewPlayer;
    private String msgUsage;
    private String msgNotNewPlayer;
    private String msgAlreadyWelcomed;
    private String msgOwnWelcome;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("welcome") != null) {
            getCommand("welcome").setExecutor(this);
            getCommand("welcome").setTabCompleter(this);
        }
        if (getCommand("welcomereward") != null) {
            getCommand("welcomereward").setExecutor(this);
        }

        getLogger().info("WelcomeReward enabled - welcome window: " + windowSeconds + "s.");
    }

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();

        windowSeconds = cfg.getInt("window-seconds", 30);
        rewardCommands = cfg.getStringList("reward-commands");

        msgRewarded = colorize(cfg.getString("messages.rewarded",
                "&aThanks for welcoming &e%target%&a! You received a reward."));
        msgNewPlayer = colorize(cfg.getString("messages.new-player-join",
                "&e%player% is on the server for the first time! Type &6/welcome %player% &ewithin &6%seconds% seconds &eto get a reward."));
        msgUsage = colorize(cfg.getString("messages.usage",
                "&eUsage: /welcome <nick>"));
        msgNotNewPlayer = colorize(cfg.getString("messages.not-a-new-player",
                "&cThere is no new player with that name to welcome right now."));
        msgAlreadyWelcomed = colorize(cfg.getString("messages.already-welcomed",
                "&cYou have already welcomed this player."));
        msgOwnWelcome = colorize(cfg.getString("messages.cannot-welcome-self",
                "&cYou can't welcome yourself."));
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

        ActiveSession session = new ActiveSession(player.getUniqueId(), player.getName());
        activeSessions.add(session);

        String announce = msgNewPlayer
                .replace("%player%", player.getName())
                .replace("%seconds%", String.valueOf(windowSeconds));
        Bukkit.broadcastMessage(announce);

        // Remove the session once the time window has passed - no one can be rewarded for this player after that
        Bukkit.getScheduler().runTaskLater(this, () -> activeSessions.remove(session), windowSeconds * 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("welcomereward")) {
            if (!sender.hasPermission("welcomereward.admin")) {
                sender.sendMessage(colorize("&cYou don't have permission."));
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfigValues();
                sender.sendMessage(colorize("&aWelcomeReward: config reloaded."));
                return true;
            }
            sender.sendMessage(colorize("&eUsage: /welcomereward reload"));
            return true;
        }

        // /welcome <nick> (and its aliases)
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(msgUsage);
            return true;
        }

        handleWelcome((Player) sender, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("welcome") || args.length != 1) {
            return Collections.emptyList();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        UUID senderId = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;

        List<String> result = new ArrayList<>();
        synchronized (activeSessions) {
            for (ActiveSession session : activeSessions) {
                if (session.newPlayerUuid.equals(senderId)) {
                    continue; // don't suggest yourself
                }
                if (session.newPlayerName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(session.newPlayerName);
                }
            }
        }
        return result;
    }

    private void handleWelcome(Player sender, String nick) {
        ActiveSession target = null;
        synchronized (activeSessions) {
            for (ActiveSession session : activeSessions) {
                if (session.newPlayerName.equalsIgnoreCase(nick)) {
                    target = session;
                    break;
                }
            }
        }

        if (target == null) {
            sender.sendMessage(msgNotNewPlayer);
            return;
        }

        if (target.newPlayerUuid.equals(sender.getUniqueId())) {
            sender.sendMessage(msgOwnWelcome);
            return;
        }

        if (!target.rewarded.add(sender.getUniqueId())) {
            sender.sendMessage(msgAlreadyWelcomed);
            return;
        }

        reward(sender, target.newPlayerName);
    }

    private void reward(Player player, String targetName) {
        // Called from the command, which already runs on the main thread
        for (String cmd : rewardCommands) {
            String finalCmd = cmd.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
        }
        player.sendMessage(msgRewarded.replace("%target%", targetName));
    }

    private static class ActiveSession {
        final UUID newPlayerUuid;
        final String newPlayerName;
        final Set<UUID> rewarded = ConcurrentHashMap.newKeySet();

        ActiveSession(UUID newPlayerUuid, String newPlayerName) {
            this.newPlayerUuid = newPlayerUuid;
            this.newPlayerName = newPlayerName;
        }
    }
}
