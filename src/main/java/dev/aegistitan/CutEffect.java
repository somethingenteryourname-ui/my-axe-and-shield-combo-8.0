package dev.aegistitan;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Random;

/** The animation of a wall being sliced in two and falling apart. */
final class CutEffect {

    private static final Particle.DustTransition BROKEN = Fx.fade(0x3FE0FF, 0xFF3030, 1.5f);
    private static final Particle.DustOptions BROKEN_LATTICE = Fx.dust(0x6A7BFF, 1.0f);
    private static final Particle.DustOptions SLASH = Fx.dust(0xFFFFFF, 2.2f);
    private static final Particle.DustOptions SLASH_HOT = Fx.dust(0xFF9A3C, 1.8f);

    private final World world;
    private final Vector center;
    private final Vector normal;
    private final Vector right;
    private final Vector up;
    private final ShieldShape shape;
    private final double cutU;
    private final Random random;
    private int age;

    CutEffect(Wall wall, double cutU, Random random) {
        this.world = wall.world;
        this.center = wall.center.clone();
        this.normal = wall.normal.clone();
        this.right = wall.right.clone();
        this.up = wall.up.clone();
        this.shape = wall.shape;
        this.cutU = cutU;
        this.random = random;
    }

    /** @return false when finished */
    boolean tick() {
        int t = age++;
        double h = shape.h;

        if (t == 0) {
            Location loc = center.toLocation(world);
            world.playSound(loc, Sound.ITEM_SHIELD_BREAK, 2f, 0.5f);
            world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 2f, 0.6f);
            world.playSound(loc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.5f, 0.5f);
            world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 2f, 0.5f);
            world.playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.6f);
        }

        // 1) A blazing slash racing down the wall
        if (t <= 5) {
            double progress = (t + 1) / 6.0;
            double bottom = h / 2 - h * progress;
            for (double v = h / 2; v >= bottom; v -= 0.25) {
                if (!shape.contains(cutU, v)) {
                    continue;
                }
                double jitter = (random.nextDouble() - 0.5) * 0.3;
                Vector p = point(cutU + jitter, v, 0.05);
                Fx.dust(world, p, (random.nextBoolean() ? SLASH : SLASH_HOT));
                if (random.nextInt(4) == 0) {
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 2, 0.1, 0.2);
                }
            }
            Vector tip = point(cutU, Math.max(-h / 2, bottom), 0.1);
            Fx.spawn(world, Particle.SWEEP_ATTACK, tip, 2, 0.3, 0);
            Fx.spawn(world, Particle.FLAME, tip, 6, 0.2, 0.05);
            Fx.spawn(world, Particle.END_ROD, tip, 3, 0.1, 0.05);
        }

        // 2) The two halves split, tilt apart and fall while fading out
        if (t >= 3) {
            double k = t - 3;
            double sep = 0.12 * k + 0.02 * k * k;
            double drop = 0.015 * k * k;
            double tilt = Math.min(0.9, 0.045 * k);
            double density = 1 - k / 24.0;
            if (density <= 0) {
                return false;
            }
            drawHalf(shape.outline, sep, drop, tilt, density, true);
            drawHalf((t % 2 == 0) ? shape.latticeA : shape.latticeB, sep, drop, tilt, density * 0.7, false);

            // Crackling along both cut edges
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < 6; i++) {
                    double v = -h / 2 + random.nextDouble() * h;
                    if (!shape.contains(cutU, v)) {
                        continue;
                    }
                    Vector p = moved(cutU + side * 0.1, v, side, sep, drop, tilt);
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 1, 0.05, 0.1);
                }
            }
            // Shards raining off
            for (int i = 0; i < 4; i++) {
                double[] r = shape.randomInside(random);
                if (r != null) {
                    int side = r[0] < cutU ? -1 : 1;
                    Vector p = moved(r[0], r[1], side, sep, drop, tilt);
                    Fx.move(world, Particle.END_ROD, p, new Vector(side * 0.3, -1, 0), 0.25);
                }
            }
        }
        return t < 28;
    }

    private void drawHalf(double[] pts, double sep, double drop, double tilt, double density, boolean outline) {
        for (int i = 0; i < pts.length; i += 2) {
            if (random.nextDouble() > density) {
                continue;
            }
            double u = pts[i];
            double v = pts[i + 1];
            int side = u < cutU ? -1 : 1;
            Vector p = moved(u, v, side, sep, drop, tilt);
            if (outline) {
                Fx.fade(world, p, BROKEN);
            } else {
                Fx.dust(world, p, BROKEN_LATTICE);
            }
        }
    }

    /** Where a point of one half ends up after splitting/tilting/falling. */
    private Vector moved(double u, double v, int side, double sep, double drop, double tilt) {
        double du = u - cutU;
        double dv = v + shape.h / 2;
        double a = -side * tilt;
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double nu = du * cos - dv * sin;
        double nv = du * sin + dv * cos;
        return point(cutU + nu + side * sep, -shape.h / 2 + nv - drop, 0);
    }

    private Vector point(double u, double v, double d) {
        return new Vector(
                center.getX() + right.getX() * u + up.getX() * v + normal.getX() * d,
                center.getY() + right.getY() * u + up.getY() * v + normal.getY() * d,
                center.getZ() + right.getZ() * u + up.getZ() * v + normal.getZ() * d);
    }
}
