package org.givinghawk.balanceTracker;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.*;
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

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(CREATE_BALANCE_TABLE);
            stmt.executeUpdate(CREATE_LAST_BALANCE_TABLE);
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
            }

            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Failed to rollback transaction", ex);
            }
            Bukkit.getLogger().log(Level.SEVERE, "Failed to record balance for player: " + playerUuid, e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                Bukkit.getLogger().log(Level.SEVERE, "Failed to reset auto-commit", e);
            }
        }
    }

    public Map<UUID, Double> getLastBalances() {
        Map<UUID, Double> balances = new HashMap<>();
        String sql = "SELECT player_uuid, balance FROM last_balances";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                double balance = rs.getDouble("balance");
                balances.put(uuid, balance);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to load last balances", e);
        }
        return balances;
    }

    public void purgeOldRecords(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        String sql = "DELETE FROM player_balances WHERE timestamp < ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            int deleted = stmt.executeUpdate();
            Bukkit.getLogger().info("Purged " + deleted + " old balance records");
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to purge old records", e);
        }
    }

    public List<BalanceRecord> getBalanceHistory(UUID playerUuid) {
        List<BalanceRecord> records = new ArrayList<>();
        String sql = "SELECT timestamp, balance FROM player_balances " +
                "WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 100";

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
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to get balance history", e);
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