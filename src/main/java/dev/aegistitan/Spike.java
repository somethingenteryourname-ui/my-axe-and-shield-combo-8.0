package dev.aegistitan;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** A big glowing rock spike that flies in an arc, stabs into the ground and shatters. */
final class Spike extends BukkitRunnable {

    static final double GRAVITY = 0.06;
    private static final int EMBED_TICKS = 14;

    private final WallManager walls;
    private final Random random;
    private final World world;
    private final Vector tail;
    private final Vector vel;
    private final double k;
    private final double length;
    private final double baseRadius;
    private final double ringStep;
    private final UUID casterId;
    private final Terrain.Scar scar;
    private final boolean crack;
    private final ItemStack chip;
    private final BlockData ground;
    private final double damage;
    private final float volume;
    private final Set<UUID> struck = new HashSet<>();

    private final Particle.DustOptions dark;
    private final Particle.DustOptions mid;
    private final Particle.DustTransition hot;
    private final Particle.DustOptions glow;

    private Vector dir;
    private int age;
    private int embedded = -1;
    private double spin;

    Spike(WallManager walls, Random random, World world, Vector start, Vector vel, double k, UUID casterId,
          Terrain.Scar scar, boolean crack, ItemStack chip, BlockData ground, double damage, float volume) {
        this.walls = walls;
        this.random = random;
        this.world = world;
        this.tail = start.clone();
        this.vel = vel.clone();
        this.k = k;
        this.length = 3.2 * Math.pow(k, 0.7);
        this.baseRadius = 0.55 * Math.pow(k, 0.6);
        this.ringStep = 0.28 * Math.pow(k, 0.45);
        this.casterId = casterId;
        this.scar = scar;
        this.crack = crack;
        this.chip = chip;
        this.ground = ground;
        this.damage = damage;
        this.volume = volume;
        this.dir = vel.clone().normalize();
        float size = (float) Math.min(4.0, 1.5 * Math.pow(k, 0.4));
        this.dark = Fx.dust(0x2E2A30, size);
        this.mid = Fx.dust(0x55505A, size);
        this.hot = Fx.fade(0xFF7A1A, 0xFFE9A0, size);
        this.glow = Fx.dust(0xFF5A10, size * 0.8f);
    }

    private Vector tip() {
        return tail.clone().add(dir.clone().multiply(length));
    }

    @Override
    public void run() {
        age++;
        spin += 0.35;

        if (embedded >= 0) {
            // Stuck in the ground, glowing, then bursts apart
            embedded++;
            draw(1.0 - embedded / (double) EMBED_TICKS, true);
            if (embedded >= EMBED_TICKS) {
                shatter(tip());
                cancel();
            }
            return;
        }

        Vector tipBefore = tip();
        vel.setY(vel.getY() - GRAVITY);
        tail.add(vel);
        dir = vel.clone().normalize();
        Vector tipNow = tip();
        double travel = tipBefore.distance(tipNow);

        // Hit a shield wall?
        WallManager.WallHit wallHit = walls.rayCast(world, tipBefore, tipNow.clone().subtract(tipBefore).normalize(),
                travel + 0.1, casterId);
        if (wallHit != null) {
            walls.impact(wallHit.wall(), wallHit.point());
            shatter(wallHit.point());
            cancel();
            return;
        }

        // Hit the ground / a mountain side?
        int steps = (int) Math.ceil(travel / 0.5) + 1;
        for (int i = 1; i <= steps; i++) {
            Vector p = tipBefore.clone().add(tipNow.clone().subtract(tipBefore).multiply(i / (double) steps));
            if (p.getY() < world.getMinHeight() || world.getBlockAt(p.getBlockX(), p.getBlockY(), p.getBlockZ()).getType().isSolid()) {
                land(p);
                return;
            }
        }

        hitEntities();
        draw(1.0, false);

        // Trail
        Fx.spawn(world, Particle.LARGE_SMOKE, tail, (int) Math.ceil(k * 0.5), 0.2 * k, 0.01);
        Fx.spawn(world, Particle.FLAME, tail, (int) Math.ceil(2 * Math.sqrt(k)), 0.15 * k, 0.02);
        Fx.move(world, Particle.ITEM, tail, new Vector(random.nextDouble() - 0.5, -0.5, random.nextDouble() - 0.5), 0.2, chip);
        if (age % 2 == 0) {
            Fx.spawn(world, Particle.ELECTRIC_SPARK, tipNow, 2, 0.1 * k, 0.05);
        }

        if (age > 160) {
            shatter(tipNow);
            cancel();
        }
    }

    private void land(Vector contact) {
        // Bury the tip a bit so it looks stabbed into the ground
        tail.copy(contact.clone().subtract(dir.clone().multiply(length * 0.7)));
        embedded = 0;
        Fx.spawn(world, Particle.BLOCK, contact, (int) (30 * Math.sqrt(k)), 0.4 * k, 0.2 * k, 0.4 * k, 0.2, ground);
        Fx.spawn(world, Particle.EXPLOSION, contact, 1, 0, 0);
        for (int i = 0; i < 25 * Math.sqrt(k); i++) {
            Vector d = new Vector(random.nextDouble() - 0.5, 0.4 + random.nextDouble() * 0.6, random.nextDouble() - 0.5);
            Fx.move(world, Particle.ITEM, contact, d, (0.2 + random.nextDouble() * 0.3) * Math.sqrt(k), chip);
        }
        Location loc = contact.toLocation(world);
        world.playSound(loc, Sound.BLOCK_POINTED_DRIPSTONE_LAND, volume * 0.7f, 0.5f);
        world.playSound(loc, Sound.BLOCK_DEEPSLATE_BREAK, volume * 0.7f, 0.6f);
        if (crack && scar != null) {
            double r = 1.5 * Math.pow(k, 0.6);
            for (int i = 0; i < 10 * Math.sqrt(k); i++) {
                double ang = random.nextDouble() * Math.PI * 2;
                double dist = random.nextDouble() * r;
                Block top = Terrain.surface(world, contact.getX() + Math.cos(ang) * dist,
                        contact.getZ() + Math.sin(ang) * dist, contact.getY());
                if (top != null) {
                    scar.crack(top, random);
                }
            }
        }
    }

    private void shatter(Vector at) {
        Fx.spawn(world, Particle.BLOCK, at, (int) (40 * Math.sqrt(k)), 0.5 * k, 0.5 * k, 0.5 * k, 0.2, ground);
        for (int i = 0; i < 40 * Math.sqrt(k); i++) {
            Vector d = new Vector(random.nextDouble() - 0.5, random.nextDouble() * 0.8, random.nextDouble() - 0.5);
            double sp = (0.2 + random.nextDouble() * 0.4) * Math.sqrt(k);
            if (random.nextBoolean()) {
                Fx.move(world, Particle.ITEM, at, d, sp, chip);
            } else {
                Fx.move(world, Particle.FIREWORK, at, d, sp * 0.4);
            }
        }
        Fx.spawn(world, Particle.LAVA, at, (int) Math.ceil(3 * Math.sqrt(k)), 0.3 * k, 0);
        Location loc = at.toLocation(world);
        world.playSound(loc, Sound.BLOCK_GLASS_BREAK, volume * 0.5f, 0.5f);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 0.4f, 1.2f);
    }

    private void hitEntities() {
        if (damage <= 0) {
            return;
        }
        Vector tipNow = tip();
        Vector middle = tail.clone().add(tipNow).multiply(0.5);
        double reach = length / 2 + baseRadius + 1;
        Player caster = Bukkit.getPlayer(casterId);
        for (Entity e : world.getNearbyEntities(middle.toLocation(world), reach, reach, reach)) {
            if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(casterId) || struck.contains(e.getUniqueId())
                    || le.isDead()) {
                continue;
            }
            if (le instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR)) {
                continue;
            }
            Vector c = le.getLocation().toVector().add(new Vector(0, le.getHeight() / 2, 0));
            if (distanceToSegment(c, tail, tipNow) > baseRadius + le.getWidth() / 2 + 0.3) {
                continue;
            }
            struck.add(e.getUniqueId());
            if (caster != null) {
                le.damage(damage, caster);
            } else {
                le.damage(damage);
            }
            le.setVelocity(dir.clone().multiply(0.9).setY(0.5));
            Fx.spawn(world, Particle.CRIT, c, 15, 0.3, 0.4);
            world.playSound(le.getLocation(), Sound.ENTITY_ARROW_HIT, 1.2f, 0.6f);
        }
    }

    static double distanceToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len2 = ab.lengthSquared();
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / len2));
        return p.distance(a.clone().add(ab.multiply(t)));
    }

    /** The spike: a spinning cone of dark rock with glowing cracks and a white-hot tip. */
    private void draw(double visibility, boolean stuck) {
        Vector ref = Math.abs(dir.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = dir.getCrossProduct(ref).normalize();
        Vector w = dir.getCrossProduct(u).normalize();
        double turn = stuck ? 0 : spin;
        for (double s = 0; s <= length; s += ringStep) {
            double f = s / length;
            double r = baseRadius * Math.pow(1 - f, 0.9);
            int pts = Math.max(3, (int) Math.round(2 * Math.PI * r / ringStep));
            double crackAngle = turn + s * 1.3;
            for (int i = 0; i < pts; i++) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                double ang = turn + i * Math.PI * 2 / pts;
                Vector p = new Vector(
                        tail.getX() + dir.getX() * s + (u.getX() * Math.cos(ang) + w.getX() * Math.sin(ang)) * r,
                        tail.getY() + dir.getY() * s + (u.getY() * Math.cos(ang) + w.getY() * Math.sin(ang)) * r,
                        tail.getZ() + dir.getZ() * s + (u.getZ() * Math.cos(ang) + w.getZ() * Math.sin(ang)) * r);
                if (f > 0.75) {
                    Fx.fade(world, p, hot);
                } else if (Math.abs(Math.sin((ang - crackAngle) / 2)) < 0.12) {
                    Fx.dust(world, p, glow);
                } else {
                    Fx.dust(world, p, (i % 2 == 0) ? dark : mid);
                }
            }
        }
        // bright point
        if (visibility >= 1 || random.nextDouble() < visibility) {
            Fx.spawn(world, Particle.END_ROD, tip(), 1, 0, 0);
        }
    }
}
