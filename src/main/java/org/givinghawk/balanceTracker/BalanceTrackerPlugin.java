package org.givinghawk.balanceTracker;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
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
    private BukkitTask topPlayersRecordingTask;
    private BukkitTask purgeTask;
    private int topPlayersRecordInterval;
    private int topPlayersCount;
    private final int purgeIntervalDays = 60; // 2 months

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

        // Get configuration values
        topPlayersRecordInterval = config.getInt("topPlayersRecordInterval", 3600) * 20; // Default: 1 hour
        topPlayersCount = config.getInt("topPlayersCount", 100); // Default: top 100 players

        // Schedule top players recording and purge tasks
        startTopPlayersRecordingTask();
        startPurgeTask();

        // Register command safely
        registerCommand("balancehistory", new BalanceHistoryCommand(databaseManager));
    }

    private void registerCommand(String commandName, CommandExecutor executor) {
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            command.setExecutor(executor);
            getLogger().info("Registered command: /" + commandName);
        } else {
            getLogger().severe("Failed to register command: /" + commandName + " - not defined in plugin.yml!");
        }
    }

    @Override
    public void onDisable() {
        // Cancel scheduled tasks
        if (topPlayersRecordingTask != null) topPlayersRecordingTask.cancel();
        if (purgeTask != null) purgeTask.cancel();

        // Close database connection
        if (databaseManager != null) databaseManager.close();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void startTopPlayersRecordingTask() {
        topPlayersRecordingTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            // Get all players who have ever played on the server
            OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();

            // Create a list of player balances
            List<PlayerBalance> playerBalances = new ArrayList<>();

            for (OfflinePlayer player : allPlayers) {
                // Skip players with no known UUID
                if (player.getUniqueId() == null) continue;

                try {
                    double balance = economy.getBalance(player);
                    String playerName = player.getName() != null ? player.getName() : "Unknown";
                    playerBalances.add(new PlayerBalance(player.getUniqueId(), balance, playerName));
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to get balance for player", e);
                }
            }

            // Sort by balance descending
            playerBalances.sort((a, b) -> Double.compare(b.balance, a.balance));

            // Record top players
            int count = Math.min(topPlayersCount, playerBalances.size());
            for (int i = 0; i < count; i++) {
                PlayerBalance pb = playerBalances.get(i);
                databaseManager.recordBalance(pb.uuid, pb.balance);
            }

            getLogger().info("Recorded balances for top " + count + " players");
        }, 0, topPlayersRecordInterval); // Start immediately, then repeat
    }

    private void startPurgeTask() {
        // Run purge daily at 4:00 AM
        long ticksPerDay = 20 * 60 * 60 * 24;
        long initialDelay = (long) (ticksPerDay * 0.16); // Approximately 4:00 AM

        purgeTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            getLogger().info("Purging old balance records (older than " + purgeIntervalDays + " days)");
            databaseManager.purgeOldRecords(purgeIntervalDays);
        }, initialDelay, ticksPerDay);
    }

    private static class PlayerBalance {
        public final UUID uuid;
        public final double balance;
        public final String name;

        public PlayerBalance(UUID uuid, double balance, String name) {
            this.uuid = uuid;
            this.balance = balance;
            this.name = name;
        }
    }
}

class DatabaseManager {
    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS player_balances (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "player_uuid VARCHAR(36) NOT NULL," +
            "timestamp BIGINT NOT NULL," +
            "balance DOUBLE NOT NULL," +
            "INDEX idx_player_uuid (player_uuid)," +
            "INDEX idx_timestamp (timestamp))";

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

    public void purgeOldRecords(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String sql = "DELETE FROM player_balances WHERE timestamp < ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cutoff);
            int deleted = statement.executeUpdate();
            Bukkit.getLogger().info("Purged " + deleted + " old balance records");
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to purge old records", e);
        }
    }

    public List<BalanceRecord> getBalanceHistory(UUID playerUuid) {
        List<BalanceRecord> records = new ArrayList<>();
        String sql = "SELECT timestamp, balance FROM player_balances WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 100";

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

class BalanceHistoryCommand implements CommandExecutor {
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

        final String playerName = args[0];
        final CommandSender finalSender = sender;

        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("BalanceTracker"), () -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            if (player == null || !player.hasPlayedBefore()) {
                finalSender.sendMessage("§cPlayer not found: " + playerName);
                return;
            }

            UUID uuid = player.getUniqueId();
            List<BalanceRecord> records = databaseManager.getBalanceHistory(uuid);

            if (records.isEmpty()) {
                finalSender.sendMessage("§eNo balance records found for " + playerName);
                return;
            }

            // Filter out consecutive records with the same balance
            List<BalanceRecord> filteredRecords = new ArrayList<>();
            double lastBalance = -1;

            for (BalanceRecord record : records) {
                if (Math.abs(record.getBalance() - lastBalance) > 0.001) {
                    filteredRecords.add(record);
                    lastBalance = record.getBalance();
                }
            }

            int endIndex = Math.min(filteredRecords.size(), 10);
            List<BalanceRecord> lastChanges = filteredRecords.subList(0, endIndex);

            finalSender.sendMessage("§6Balance changes for §e" + playerName + "§6 (last " + endIndex + " changes):");
            for (BalanceRecord record : lastChanges) {
                String time = new java.util.Date(record.getTimestamp()).toString();
                finalSender.sendMessage("§7- §a" + time + "§f: §b$" + String.format("%.2f", record.getBalance()));
            }
        });

        return true;
    }
}