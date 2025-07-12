package org.givinghawk.balanceTracker;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.*;
import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {
    private static final String CREATE_BALANCE_TABLE = "CREATE TABLE IF NOT EXISTS player_balances (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "player_uuid VARCHAR(36) NOT NULL," +
            "timestamp BIGINT NOT NULL," +
            "balance DOUBLE NOT NULL," +
            "INDEX idx_player_uuid (player_uuid)," +
            "INDEX idx_timestamp (timestamp))";

    private static final String CREATE_LAST_BALANCE_TABLE = "CREATE TABLE IF NOT EXISTS last_balances (" +
            "player_uuid VARCHAR(36) PRIMARY KEY," +
            "balance DOUBLE NOT NULL)";

    private final BalanceTrackerPlugin plugin;
    private Connection connection;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

    public DatabaseManager(BalanceTrackerPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.host = config.getString("mysql.host", "localhost");
        this.port = config.getInt("mysql.port", 3306);
        this.database = config.getString("mysql.database", "minecraft");
        this.username = config.getString("mysql.username", "user");
        this.password = config.getString("mysql.password", "pass");
        this.currencyFormat.setMaximumFractionDigits(2);
    }

    public void initializeDatabase() throws SQLException {
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&autoReconnect=true&failOverReadOnly=false";

        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(CREATE_BALANCE_TABLE);
                stmt.executeUpdate(CREATE_LAST_BALANCE_TABLE);
                logStyled("&a✔ Created database tables successfully");
            }

            // Test connection stability
            try (Statement testStmt = connection.createStatement()) {
                testStmt.executeQuery("SELECT 1");
            }

        } catch (SQLException e) {
            logStyled("&c✘ Database initialization failed: &e" + e.getMessage());
            throw e;
        }
    }

    public void recordBalance(UUID playerUuid, double balance) {
        long timestamp = System.currentTimeMillis();

        String insertHistory = "INSERT INTO player_balances (player_uuid, timestamp, balance) VALUES (?, ?, ?)";
        String updateLast = "INSERT INTO last_balances (player_uuid, balance) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE balance = ?";

        try {
            connection.setAutoCommit(false);

            try (PreparedStatement historyStmt = connection.prepareStatement(insertHistory);
                 PreparedStatement lastStmt = connection.prepareStatement(updateLast)) {

                // Insert into history
                historyStmt.setString(1, playerUuid.toString());
                historyStmt.setLong(2, timestamp);
                historyStmt.setDouble(3, balance);
                historyStmt.executeUpdate();

                // Update last balance
                lastStmt.setString(1, playerUuid.toString());
                lastStmt.setDouble(2, balance);
                lastStmt.setDouble(3, balance);
                lastStmt.executeUpdate();

                if (plugin.debugMode) {
                    logStyled("&7◆ Recorded balance for &e" + playerUuid +
                            " &7(&a" + formatCurrency(balance) + "&7)");
                }
            }

            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
                logStyled("&c⚠ Transaction rolled back for player: &e" + playerUuid);
            } catch (SQLException ex) {
                logStyled("&c✘ Failed to rollback transaction!");
                plugin.getLogger().log(Level.SEVERE, "Transaction rollback failed", ex);
            }
            logStyled("&c✘ Failed to record balance for player: &e" + playerUuid);
            plugin.getLogger().log(Level.SEVERE, "Balance recording error", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logStyled("&c⚠ Failed to reset auto-commit mode");
            }
        }
    }

    public Map<UUID, Double> getLastBalances() {
        Map<UUID, Double> balances = new HashMap<>();
        String sql = "SELECT player_uuid, balance FROM last_balances";

        long startTime = System.currentTimeMillis();
        int recordCount = 0;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                double balance = rs.getDouble("balance");
                balances.put(uuid, balance);
                recordCount++;
            }

            if (plugin.debugMode) {
                long duration = System.currentTimeMillis() - startTime;
                logStyled("&7◆ Loaded &e" + recordCount + "&7 last balances in &a" + duration + "ms");
            }

        } catch (SQLException e) {
            logStyled("&c✘ Failed to load last balances");
            plugin.getLogger().log(Level.SEVERE, "Last balances query failed", e);
        }
        return balances;
    }

    public void purgeOldRecords(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String sql = "DELETE FROM player_balances WHERE timestamp < ?";

        long startTime = System.currentTimeMillis();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            int deleted = stmt.executeUpdate();

            long duration = System.currentTimeMillis() - startTime;
            logStyled("&6■ Purged &e" + deleted + "&6 old records in &a" + duration + "ms");

        } catch (SQLException e) {
            logStyled("&c✘ Failed to purge old records");
            plugin.getLogger().log(Level.SEVERE, "Purge operation failed", e);
        }
    }

    public List<BalanceRecord> getBalanceHistory(UUID playerUuid) {
        List<BalanceRecord> records = new ArrayList<>();
        String sql = "SELECT timestamp, balance FROM player_balances " +
                "WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 100";

        long startTime = System.currentTimeMillis();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(new BalanceRecord(
                            rs.getLong("timestamp"),
                            rs.getDouble("balance")
                    ));
                }
            }

            if (plugin.debugMode) {
                long duration = System.currentTimeMillis() - startTime;
                logStyled("&7◆ Retrieved &e" + records.size() +
                        "&7 records for &e" + playerUuid + "&7 in &a" + duration + "ms");
            }

        } catch (SQLException e) {
            logStyled("&c✘ Failed to get history for player: &e" + playerUuid);
            plugin.getLogger().log(Level.SEVERE, "History query failed", e);
        }
        return records;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logStyled("&6■ Database connection closed gracefully");
            }
        } catch (SQLException e) {
            logStyled("&c⚠ Failed to close database connection");
            plugin.getLogger().log(Level.SEVERE, "Connection close failed", e);
        }
    }

    private void logStyled(String message) {
        plugin.getLogger().info(ChatColor.translateAlternateColorCodes('&', message));
    }

    private String formatCurrency(double amount) {
        return currencyFormat.format(amount).replace("$", "⛃ ");
    }
}