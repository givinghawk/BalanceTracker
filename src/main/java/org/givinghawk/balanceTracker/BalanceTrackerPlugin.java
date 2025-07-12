package org.givinghawk.balanceTracker;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BalanceTrackerPlugin extends JavaPlugin {
    private Economy economy;
    private DatabaseManager databaseManager;
    private BukkitTask balanceCheckTask;
    private int checkInterval;
    private final Map<UUID, Double> lastBalanceCache = new ConcurrentHashMap<>();
    private final int purgeIntervalDays = 60;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            databaseManager = new DatabaseManager(config);
            databaseManager.initializeDatabase();
            loadLastBalances();
            getLogger().info("Database connected successfully");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to connect to database", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        checkInterval = config.getInt("balanceCheckInterval", 300) * 20;
        startBalanceCheckTask();
        startPurgeTask();

        registerCommand("balancehistory", new BalanceHistoryCommand(databaseManager));
    }

    private void loadLastBalances() {
        lastBalanceCache.putAll(databaseManager.getLastBalances());
    }

    private void registerCommand(String commandName, BalanceHistoryCommand executor) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
            getLogger().info("Registered command: /" + commandName);
        } else {
            getLogger().severe("Failed to register command: /" + commandName);
        }
    }

    @Override
    public void onDisable() {
        if (balanceCheckTask != null) balanceCheckTask.cancel();
        if (databaseManager != null) databaseManager.close();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void startBalanceCheckTask() {
        balanceCheckTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            // Get all players who have ever played
            OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
            List<UUID> changedPlayers = new ArrayList<>();

            for (OfflinePlayer player : allPlayers) {
                UUID uuid = player.getUniqueId();
                if (uuid == null) continue;

                try {
                    double currentBalance = economy.getBalance(player);
                    Double lastBalance = lastBalanceCache.get(uuid);

                    // Record only if balance changed
                    if (lastBalance == null || Math.abs(currentBalance - lastBalance) > 0.001) {
                        databaseManager.recordBalance(uuid, currentBalance);
                        lastBalanceCache.put(uuid, currentBalance);
                        changedPlayers.add(uuid);
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to check balance for player", e);
                }
            }

            if (!changedPlayers.isEmpty()) {
                getLogger().info("Recorded balances for " + changedPlayers.size() + " players with changes");
            }
        }, 0, checkInterval);
    }

    private void startPurgeTask() {
        long ticksPerDay = 20 * 60 * 60 * 24;
        long initialDelay = (long) (ticksPerDay * 0.16); // 4:00 AM

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            getLogger().info("Purging old balance records (older than " + purgeIntervalDays + " days)");
            databaseManager.purgeOldRecords(purgeIntervalDays);
        }, initialDelay, ticksPerDay);
    }
}