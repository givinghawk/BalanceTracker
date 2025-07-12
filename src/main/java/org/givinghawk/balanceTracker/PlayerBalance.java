package org.givinghawk.balanceTracker;

import java.util.UUID;

public class PlayerBalance {
    public final UUID uuid;
    public final double balance;
    public final String name;

    public PlayerBalance(UUID uuid, double balance, String name) {
        this.uuid = uuid;
        this.balance = balance;
        this.name = name;
    }
}