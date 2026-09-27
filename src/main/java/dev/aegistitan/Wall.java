package dev.aegistitan;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One player's active energy wall. */
final class Wall {

    static final int DEPLOY_TICKS = 8;
    static final int RETRACT_TICKS = 7;

    final UUID owner;
    final ShieldShape shape;

    World world;
    Vector center;
    Vector normal;   // points away from the owner (the "front" of the wall)
    Vector right;
    Vector up;

    int age;
    int retractAge = -1;

    /** Ripples from hits: {u, v, age} in shape units. */
    final List<double[]> ripples = new ArrayList<>();
    /** Which side of the wall each nearby entity was last on (+1 front, -1 back). */
    final Map<UUID, Integer> sides = new HashMap<>();
    /** Cooldown so bump sounds don't spam. */
    final Map<UUID, Integer> bumpCooldown = new HashMap<>();

    Wall(UUID owner, ShieldShape shape) {
        this.owner = owner;
        this.shape = shape;
    }

    boolean retracting() {
        return retractAge >= 0;
    }

    boolean finished() {
        return retractAge >= RETRACT_TICKS;
    }

    /** Wall is solid (blocks things) once it has mostly opened and until it starts closing. */
    boolean solid() {
        return !retracting() && age >= 3;
    }

    /** Size multiplier used for the open/close animation. */
    double scale() {
        if (retracting()) {
            double t = Math.min(1, retractAge / (double) RETRACT_TICKS);
            return 1 - t * t;
        }
        double t = Math.min(1, age / (double) DEPLOY_TICKS);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /** Point the wall where the player is looking. */
    void aim(Player player, double distance) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        if (normal == null) {
            normal = dir.clone();
        } else {
            normal = normal.clone().multiply(0.55).add(dir.clone().multiply(0.45));
            if (normal.lengthSquared() < 1e-6) {
                normal = dir.clone();
            }
            normal.normalize();
        }
        Vector worldUp = new Vector(0, 1, 0);
        right = normal.getCrossProduct(worldUp);
        if (right.lengthSquared() < 1e-4) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        right.normalize();
        up = right.getCrossProduct(normal).normalize();

        // When looking straight ahead the bottom of the wall sits at the player's feet.
        double level = Math.sqrt(normal.getX() * normal.getX() + normal.getZ() * normal.getZ());
        center = eye.toVector()
                .add(normal.clone().multiply(distance))
                .add(up.clone().multiply((shape.h / 2 - 1.9) * level));
        world = player.getWorld();
    }

    /** World position of a point on the wall (u, v along the wall, d out of it). */
    Vector point(double u, double v, double d) {
        return new Vector(
                center.getX() + right.getX() * u + up.getX() * v + normal.getX() * d,
                center.getY() + right.getY() * u + up.getY() * v + normal.getY() * d,
                center.getZ() + right.getZ() * u + up.getZ() * v + normal.getZ() * d);
    }

    /** {u, v, d} of a world position relative to the wall. d > 0 = in front. */
    double[] local(Vector p) {
        double x = p.getX() - center.getX();
        double y = p.getY() - center.getY();
        double z = p.getZ() - center.getZ();
        return new double[]{
                x * right.getX() + y * right.getY() + z * right.getZ(),
                x * up.getX() + y * up.getY() + z * up.getZ(),
                x * normal.getX() + y * normal.getY() + z * normal.getZ()};
    }

    /** Is (u, v) (in world blocks, not shape units) inside the wall at its current size? */
    boolean containsLocal(double u, double v) {
        double s = scale();
        if (s < 0.15) {
            return false;
        }
        return shape.contains(u / s, v / s);
    }

    /** Closest point on the wall to p. */
    Vector nearestPoint(Vector p) {
        double[] l = local(p);
        double s = Math.max(0.15, scale());
        double v = Math.max(-shape.h / 2, Math.min(shape.h / 2, l[1] / s));
        double hw = Math.max(0, shape.halfWidth(v));
        double u = Math.max(-hw, Math.min(hw, l[0] / s));
        return point(u * s, v * s, 0);
    }

    void addRipple(double u, double v) {
        if (ripples.size() < 8) {
            ripples.add(new double[]{u, v, 0});
        }
    }
}
