package org.givinghawk.balanceTracker;

public class BalanceRecord {
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