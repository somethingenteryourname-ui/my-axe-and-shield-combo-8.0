package dev.aegistitan;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** Small helpers so the effect code stays readable. */
final class Fx {

    private Fx() {
    }

    static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB(rgb), size);
    }

    static Particle.DustTransition fade(int fromRgb, int toRgb, float size) {
        return new Particle.DustTransition(Color.fromRGB(fromRgb), Color.fromRGB(toRgb), size);
    }

    static void dust(World world, Vector p, Particle.DustOptions options) {
        world.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, options, true);
    }

    static void fade(World world, Vector p, Particle.DustTransition options) {
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, options, true);
    }

    /** A particle with no extra data, spread evenly in every direction. */
    static void spawn(World world, Particle type, Vector p, int count, double spread, double speed) {
        world.spawnParticle(type, p.getX(), p.getY(), p.getZ(), count, spread, spread, spread, speed, null, true);
    }

    /** Full control version (data may be null for particles that don't need it). */
    static <T> void spawn(World world, Particle type, Vector p, int count,
                          double sx, double sy, double sz, double speed, T data) {
        world.spawnParticle(type, p.getX(), p.getY(), p.getZ(), count, sx, sy, sz, speed, data, true);
    }

    /** Moving particle that needs extra data (e.g. an ItemStack for ITEM chips). */
    static <T> void move(World world, Particle type, Vector p, Vector dir, double speed, T data) {
        world.spawnParticle(type, p.getX(), p.getY(), p.getZ(), 0, dir.getX(), dir.getY(), dir.getZ(), speed, data, true);
    }

    /** Single particle that moves in a direction (count 0 = offsets become the motion). */
    static void move(World world, Particle type, Vector p, Vector dir, double speed) {
        world.spawnParticle(type, p.getX(), p.getY(), p.getZ(), 0, dir.getX(), dir.getY(), dir.getZ(), speed, null, true);
    }
}
