package org.givinghawk.balanceTracker;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.*;

public class BalanceHistoryCommand implements CommandExecutor {
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
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("BalanceTracker"), () -> {
            OfflinePlayer player = findPlayer(playerName);
            if (player == null || player.getUniqueId() == null) {
                sender.sendMessage("§cPlayer not found: " + playerName);
                return;
            }

            UUID uuid = player.getUniqueId();
            List<BalanceRecord> records = databaseManager.getBalanceHistory(uuid);

            if (records.isEmpty()) {
                sender.sendMessage("§eNo balance records found for " + playerName);
                return;
            }

            List<BalanceRecord> changes = findSignificantChanges(records);
            displayBalanceChanges(sender, playerName, changes);
        });

        return true;
    }

    private OfflinePlayer findPlayer(String name) {
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(player.getName())) {
                return player;
            }
        }
        return null;
    }

    private List<BalanceRecord> findSignificantChanges(List<BalanceRecord> records) {
        List<BalanceRecord> changes = new ArrayList<>();
        double lastBalance = -1;

        for (BalanceRecord record : records) {
            if (lastBalance == -1 || Math.abs(record.getBalance() - lastBalance) > 0.001) {
                changes.add(record);
                lastBalance = record.getBalance();
            }
        }
        return changes;
    }

    private void displayBalanceChanges(CommandSender sender, String playerName, List<BalanceRecord> changes) {
        int displayCount = Math.min(changes.size(), 10);

        sender.sendMessage("§6Balance changes for §e" + playerName + "§6 (last " + displayCount + " changes):");
        for (int i = 0; i < displayCount; i++) {
            BalanceRecord record = changes.get(i);
            String time = new Date(record.getTimestamp()).toString();
            String balance = String.format("$%.2f", record.getBalance());
            sender.sendMessage("§7- §a" + time + "§f: §b" + balance);
        }
    }
}