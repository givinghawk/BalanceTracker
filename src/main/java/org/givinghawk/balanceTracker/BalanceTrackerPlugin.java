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
import java.util.*;
import java.util.logging.Level;

public class BalanceTrackerPlugin extends JavaPlugin {

    private Economy economy;
    private DatabaseManager databaseManager;
    private BukkitTask onlineRecordingTask;
    private BukkitTask topPlayersRecordingTask;
    private int recordInterval;
    private int topPlayersRecordInterval;
    private int topPlayersCount;

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
        recordInterval = config.getInt("balanceRecordInterval", 300) * 20; // Convert to ticks
        topPlayersRecordInterval = config.getInt("topPlayersRecordInterval", 3600) * 20; // Default: 1 hour
        topPlayersCount = config.getInt("topPlayersCount", 100); // Default: top 100 players

        // Schedule balance recording tasks
        startOnlineRecordingTask();
        startTopPlayersRecordingTask();

        // Register command
        getCommand("balancehistory").setExecutor(new BalanceHistoryCommand(databaseManager));
        getCommand("baltop").setExecutor(new BalTopCommand(databaseManager, economy));
    }

    @Override
    public void onDisable() {
        // Cancel scheduled tasks
        if (onlineRecordingTask != null) onlineRecordingTask.cancel();
        if (topPlayersRecordingTask != null) topPlayersRecordingTask.cancel();

        // Close database connection
        if (databaseManager != null) databaseManager.close();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void startOnlineRecordingTask() {
        onlineRecordingTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                double balance = economy.getBalance(player);
                databaseManager.recordBalance(player.getUniqueId(), balance);
            }
        }, recordInterval, recordInterval);
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
                    // Create a final reference for use in lambda
                    final OfflinePlayer finalPlayer = player;
                    playerBalances.add(new PlayerBalance(player.getUniqueId(), balance, player.getName()));
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to get balance for " + player.getName(), e);
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
        }, topPlayersRecordInterval, topPlayersRecordInterval);
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

    public List<PlayerBalance> getTopBalances(int limit) {
        List<PlayerBalance> topBalances = new ArrayList<>();
        String sql = "SELECT p1.player_uuid, p1.balance " +
                "FROM player_balances p1 " +
                "INNER JOIN (" +
                "    SELECT player_uuid, MAX(timestamp) AS max_timestamp " +
                "    FROM player_balances " +
                "    GROUP BY player_uuid" +
                ") p2 ON p1.player_uuid = p2.player_uuid AND p1.timestamp = p2.max_timestamp " +
                "ORDER BY p1.balance DESC " +
                "LIMIT ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                    double balance = resultSet.getDouble("balance");
                    topBalances.add(new PlayerBalance(uuid, balance, null));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to retrieve top balances", e);
        }
        return topBalances;
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

class PlayerBalance {
    private final UUID uuid;
    private final double balance;
    private final String name;

    public PlayerBalance(UUID uuid, double balance, String name) {
        this.uuid = uuid;
        this.balance = balance;
        this.name = name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public double getBalance() {
        return balance;
    }

    public String getName() {
        return name;
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

        // Create final reference for lambda
        final String playerName = args[0];
        final CommandSender finalSender = sender;

        Bukkit.getScheduler().runTaskAsynchronously((JavaPlugin) Bukkit.getPluginManager().getPlugin("BalanceTracker"), () -> {
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

class BalTopCommand implements org.bukkit.command.CommandExecutor {
    private final DatabaseManager databaseManager;
    private final Economy economy;

    public BalTopCommand(DatabaseManager databaseManager, Economy economy) {
        this.databaseManager = databaseManager;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int limit = 10;
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
                limit = Math.min(Math.max(limit, 1), 100); // Clamp between 1 and 100
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number: " + args[0]);
                return true;
            }
        }

        // Create final reference for lambda
        final int finalLimit = limit;
        final CommandSender finalSender = sender;

        Bukkit.getScheduler().runTaskAsynchronously((JavaPlugin) Bukkit.getPluginManager().getPlugin("BalanceTracker"), () -> {
            List<PlayerBalance> topBalances = databaseManager.getTopBalances(finalLimit);

            if (topBalances.isEmpty()) {
                finalSender.sendMessage("§eNo balance data available");
                return;
            }

            // Get player names
            List<String> topPlayers = new ArrayList<>();
            for (int i = 0; i < topBalances.size(); i++) {
                PlayerBalance pb = topBalances.get(i);
                OfflinePlayer player = Bukkit.getOfflinePlayer(pb.getUuid());
                String name = player.getName() != null ? player.getName() : pb.getUuid().toString();
                topPlayers.add(String.format("§6%d. §e%s§f: §a$%,.2f",
                        i + 1, name, pb.getBalance()));
            }

            finalSender.sendMessage("§6=== Top " + finalLimit + " Richest Players ===");
            topPlayers.forEach(finalSender::sendMessage);
        });

        return true;
    }
}