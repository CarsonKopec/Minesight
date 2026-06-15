package com.minesight.farm;

/** An integer block position - the unit the bot pathfinder + executor work in. */
public record BotPos(int x, int y, int z) {

    public BotPos up() {
        return new BotPos(x, y + 1, z);
    }

    public BotPos down() {
        return new BotPos(x, y - 1, z);
    }

    public BotPos add(int dx, int dy, int dz) {
        return new BotPos(x + dx, y + dy, z + dz);
    }

    public double dist(BotPos o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public long sqDist(BotPos o) {
        long dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
