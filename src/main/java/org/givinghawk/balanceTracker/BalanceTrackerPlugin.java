package org.givinghawk.balanceTracker;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
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
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss");
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
    public boolean debugMode = false;

    @Override
    public void onEnable() {
        timeFormat.setTimeZone(TimeZone.getDefault());
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        debugMode = config.getBoolean("debug", false);

        // Styled startup header
        logStyled("&3&l========================================");
        logStyled("&b&lBalanceTracker &f- &aEconomy History Plugin");
        logStyled("&fVersion: &e" + getDescription().getVersion());
        logStyled("&3&l========================================");

        if (!setupEconomy()) {
            logStyled("&c✘ Vault economy not found! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            databaseManager = new DatabaseManager(this, config);
            databaseManager.initializeDatabase();
            loadLastBalances();
            logStyled("&a✔ Database connected successfully");
        } catch (Exception e) {
            logStyled("&c✘ Failed to connect to database: &e" + e.getMessage());
            if (debugMode) {
                getLogger().log(Level.SEVERE, "Database connection error", e);
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        checkInterval = config.getInt("balanceCheckInterval", 300) * 20;
        startBalanceCheckTask();
        startPurgeTask();

        registerCommand("balancehistory", new BalanceHistoryCommand(databaseManager));

        logStyled("&a✔ Plugin enabled successfully");
    }

    private void loadLastBalances() {
        int count = lastBalanceCache.size();
        lastBalanceCache.clear();
        lastBalanceCache.putAll(databaseManager.getLastBalances());
        logStyled("&a✔ Loaded &e" + lastBalanceCache.size() + "&a player balances from database");
    }

    private void registerCommand(String commandName, BalanceHistoryCommand executor) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
            logStyled("&a✔ Registered command: &e/" + commandName);
        } else {
            logStyled("&c✘ Failed to register command: &e/" + commandName);
        }
    }

    @Override
    public void onDisable() {
        if (balanceCheckTask != null) {
            balanceCheckTask.cancel();
            logStyled("&6■ Balance check task stopped");
        }
        if (databaseManager != null) {
            databaseManager.close();
            logStyled("&6■ Database connection closed");
        }
        logStyled("&c✘ Plugin disabled");
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            logStyled("&c✘ Vault economy provider not registered!");
            return false;
        }
        economy = rsp.getProvider();
        if (economy == null) {
            logStyled("&c✘ No economy plugin found via Vault!");
            return false;
        }
        logStyled("&a✔ Economy provider: &e" + economy.getName());
        return true;
    }

    private void startBalanceCheckTask() {
        balanceCheckTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
            List<UUID> changedPlayers = new ArrayList<>();
            int totalPlayers = 0;

            if (debugMode) {
                logStyled("&7◆ Starting balance check for &e" + allPlayers.length + "&7 players");
            }

            for (OfflinePlayer player : allPlayers) {
                UUID uuid = player.getUniqueId();
                if (uuid == null) continue;
                totalPlayers++;

                try {
                    double currentBalance = economy.getBalance(player);
                    Double lastBalance = lastBalanceCache.get(uuid);

                    if (lastBalance == null) {
                        // New player detected
                        if (debugMode) {
                            String name = player.getName() != null ? player.getName() : "Unknown";
                            logStyled("&7◆ New player detected: &e" + name + " &7(&a" +
                                    formatCurrency(currentBalance) + "&7)");
                        }
                        databaseManager.recordBalance(uuid, currentBalance);
                        lastBalanceCache.put(uuid, currentBalance);
                        changedPlayers.add(uuid);
                    } else if (Math.abs(currentBalance - lastBalance) > 0.001) {
                        // Balance changed
                        if (debugMode) {
                            String name = player.getName() != null ? player.getName() : "Unknown";
                            logStyled("&7◆ Balance change: &e" + name +
                                    " &7(&e" + formatCurrency(lastBalance) + " &6→ &a" +
                                    formatCurrency(currentBalance) + "&7)");
                        }
                        databaseManager.recordBalance(uuid, currentBalance);
                        lastBalanceCache.put(uuid, currentBalance);
                        changedPlayers.add(uuid);
                    }
                } catch (Exception e) {
                    String playerName = player.getName() != null ? player.getName() : uuid.toString();
                    logStyled("&c⚠ Failed to check balance for player: &e" + playerName);
                    if (debugMode) {
                        getLogger().log(Level.WARNING, "Balance check error", e);
                    }
                }
            }

            if (!changedPlayers.isEmpty()) {
                logStyled("&a✔ Recorded &e" + changedPlayers.size() + "&a balance changes " +
                        "(&7" + totalPlayers + "&a players checked)");
            } else if (debugMode) {
                logStyled("&7◆ No balance changes detected (&e" + totalPlayers + "&7 players checked)");
            }
        }, 0, checkInterval);

        logStyled("&a✔ Balance check task started &7(Interval: &e" + (checkInterval/20) + "s&7)");
    }

    private void startPurgeTask() {
        long ticksPerDay = 20 * 60 * 60 * 24;
        long initialDelay = (long) (ticksPerDay * 0.16); // 4:00 AM

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            logStyled("&6■ Starting purge of records older than &e" + purgeIntervalDays + "&6 days");
            databaseManager.purgeOldRecords(purgeIntervalDays);
        }, initialDelay, ticksPerDay);

        String nextRun = timeFormat.format(new Date(System.currentTimeMillis() + initialDelay * 50));
        logStyled("&a✔ Purge task scheduled &7(Next run: &e" + nextRun + "&7)");
    }

    private void logStyled(String message) {
        getLogger().info(ChatColor.translateAlternateColorCodes('&', message));
    }

    private String formatCurrency(double amount) {
        return currencyFormat.format(amount).replace("$", "⛃ "); // Using gold ingot symbol
    }
}