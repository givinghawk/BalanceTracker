package org.givinghawk.balanceTracker;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class BalanceTrackerPlugin extends JavaPlugin {

    private Economy economy;
    private DatabaseManager databaseManager;
    private BukkitTask recordingTask;
    private int recordInterval;

    @Override
    public void onEnable() {
        // Load configuration
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Initialize economy
        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize database
        try {
            databaseManager = new DatabaseManager(config);
            databaseManager.initializeDatabase();
            getLogger().info("Database connected successfully");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to connect to database", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Schedule balance recording
        recordInterval = config.getInt("balanceRecordInterval", 300) * 20; // Convert to ticks
        startRecordingTask();

        // Register command
        getCommand("balancehistory").setExecutor(new BalanceHistoryCommand(databaseManager));
    }

    @Override
    public void onDisable() {
        // Cancel scheduled tasks
        if (recordingTask != null) {
            recordingTask.cancel();
        }

        // Close database connection
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void startRecordingTask() {
        recordingTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                double balance = economy.getBalance(player);
                databaseManager.recordBalance(player.getUniqueId(), balance);
            }
        }, recordInterval, recordInterval);
    }
}

class DatabaseManager {
    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS player_balances (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "player_uuid VARCHAR(36) NOT NULL," +
            "timestamp BIGINT NOT NULL," +
            "balance DOUBLE NOT NULL)";

    private Connection connection;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    public DatabaseManager(FileConfiguration config) {
        this.host = config.getString("mysql.host", "localhost");
        this.port = config.getInt("mysql.port", 3306);
        this.database = config.getString("mysql.database", "minecraft");
        this.username = config.getString("mysql.username", "user");
        this.password = config.getString("mysql.password", "pass");
    }

    public void initializeDatabase() throws SQLException {
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database;
        connection = DriverManager.getConnection(jdbcUrl, username, password);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE_SQL);
        }
    }

    public void recordBalance(UUID playerUuid, double balance) {
        String sql = "INSERT INTO player_balances (player_uuid, timestamp, balance) VALUES (?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setDouble(3, balance);
            statement.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to record balance for player: " + playerUuid, e);
        }
    }

    public List<BalanceRecord> getBalanceHistory(UUID playerUuid) {
        List<BalanceRecord> records = new ArrayList<>();
        String sql = "SELECT timestamp, balance FROM player_balances WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 100";  // Fetch more records for filtering

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long timestamp = resultSet.getLong("timestamp");
                    double balance = resultSet.getDouble("balance");
                    records.add(new BalanceRecord(timestamp, balance));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to retrieve balance history for player: " + playerUuid, e);
        }
        return records;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to close database connection", e);
        }
    }
}

class BalanceRecord {
    private final long timestamp;
    private final double balance;

    public BalanceRecord(long timestamp, double balance) {
        this.timestamp = timestamp;
        this.balance = balance;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getBalance() {
        return balance;
    }
}

class BalanceHistoryCommand implements org.bukkit.command.CommandExecutor {
    private final DatabaseManager databaseManager;

    public BalanceHistoryCommand(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /balancehistory <player>");
            return true;
        }

        String playerName = args[0];
        Bukkit.getScheduler().runTaskAsynchronously((JavaPlugin) Bukkit.getPluginManager().getPlugin("BalanceTracker"), () -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            if (player == null || !player.hasPlayedBefore()) {
                sender.sendMessage("§cPlayer not found: " + playerName);
                return;
            }

            UUID uuid = player.getUniqueId();
            List<BalanceRecord> records = databaseManager.getBalanceHistory(uuid);

            if (records.isEmpty()) {
                sender.sendMessage("§eNo balance records found for " + playerName);
                return;
            }

            // Filter out consecutive records with the same balance
            List<BalanceRecord> filteredRecords = new ArrayList<>();
            double lastBalance = -1; // Initialize with impossible value

            for (BalanceRecord record : records) {
                // Use epsilon comparison for floating point values
                if (Math.abs(record.getBalance() - lastBalance) > 0.001) {
                    filteredRecords.add(record);
                    lastBalance = record.getBalance();
                }
            }

            // Limit to last 10 changes
            int endIndex = Math.min(filteredRecords.size(), 10);
            List<BalanceRecord> lastChanges = filteredRecords.subList(0, endIndex);

            sender.sendMessage("§6Balance changes for §e" + playerName + "§6 (last " + endIndex + " changes):");
            for (BalanceRecord record : lastChanges) {
                String time = new java.util.Date(record.getTimestamp()).toString();
                sender.sendMessage("§7- §a" + time + "§f: §b$" + String.format("%.2f", record.getBalance()));
            }
        });

        return true;
    }
}