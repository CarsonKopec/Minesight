package com.minesight.farm;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bot body backed by a Bukkit {@link Zombie} we drive by hand: teleport
 * cell-to-cell, break/place blocks via the world API. No NMS, fully API-safe;
 * movement is scripted (not real player physics). The reliable fallback when the
 * NMS {@link NmsBot} can't spawn ({@code -Dminesight.bot=zombie}).
 */
public final class ZombieBot extends BotEpisode {

    private static final int WALK_TICKS = 5;
    private static final int SPRINT_TICKS = 3;

    private LivingEntity entity;
    private int moveCountdown;

    public ZombieBot(JavaPlugin plugin, ArenaManager.Arena arena, BotParams params, int budget) {
        super(plugin, arena, params, budget);
    }

    @Override
    public void spawn(String name) {
        entity = world.spawn(center(pos), Zombie.class, z -> {
            z.setAI(false);
            z.setSilent(true);
            z.setInvulnerable(true);
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            z.customName(net.kyori.adventure.text.Component.text(name));
            z.setCustomNameVisible(true);
            z.setShouldBurnInDay(false);
            z.setGlowing(true);   // see the bot through walls
            if (z.getEquipment() != null) {
                z.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_PICKAXE));
            }
        });
    }

    @Override
    protected void moveBody(BotPos to) {
        if (entity != null && !entity.isDead()) {
            entity.teleport(center(to));
        }
    }

    @Override
    protected void startMove(BotPos target) {
        moveCountdown = pos.dist(goal) > params.sprintDist ? SPRINT_TICKS : WALK_TICKS;
    }

    @Override
    protected boolean tickMove(BotPos target) {
        if (--moveCountdown > 0) {
            return false;
        }
        moveBody(target);   // discrete hop to the next cell
        return true;
    }

    @Override
    protected boolean moveStuck() {
        return false;       // discrete stepping never stalls
    }

    @Override
    protected void breakBlock(BotPos b) {
        world.getBlockAt(b.x(), b.y(), b.z()).setType(Material.AIR, false);
    }

    @Override
    protected void placeBlock(BotPos b, Material m) {
        world.getBlockAt(b.x(), b.y(), b.z()).setType(m, false);
    }

    @Override
    protected void swingBody() {
        if (entity != null && !entity.isDead()) {
            entity.swingMainHand();
        }
    }

    @Override
    public void cleanup() {
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }
}
