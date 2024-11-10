package io.github.restioson.koth.game;

import xyz.nucleoid.plasmid.api.util.PlayerRef;

public class AttackRecord {
    public static final long EXPIRE_TIME = 20 * 5;

    public final PlayerRef player;
    private final long expireTime;

    public AttackRecord(PlayerRef player, long time) {
        this.player = player;
        this.expireTime = time + EXPIRE_TIME;
    }

    public boolean isValid(long time) {
        return time < this.expireTime;
    }
}
