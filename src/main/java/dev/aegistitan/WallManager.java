package dev.aegistitan;

import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Runs every energy wall: showing it, blocking things, and shattering it. */
final class WallManager implements Listener {

    record WallHit(Wall wall, Vector point, double distance) {
    }

    // Colours
    private static final Particle.DustTransition OUTLINE = Fx.fade(0x3FE0FF, 0xFFFFFF, 1.6f);
    private static final Particle.DustOptions LATTICE_DIM = Fx.dust(0x2B6BFF, 1.0f);
    private static final Particle.DustOptions LATTICE_MID = Fx.dust(0x3FB8FF, 1.1f);
    private static final Particle.DustOptions LATTICE_BRIGHT = Fx.dust(0xC8F8FF, 1.3f);
    private static final Particle.DustOptions EMBLEM = Fx.dust(0xB44CFF, 1.4f);
    private static final Particle.DustOptions EMBLEM_CORE = Fx.dust(0xFF7BFF, 1.2f);
    private static final Particle.DustOptions RIPPLE = Fx.dust(0xFFFFFF, 1.3f);

    private final AegisTitan plugin;
    private final Items items;
    private final NamespacedKey widthKey;
    private final NamespacedKey heightKey;
    private final Map<UUID, Wall> walls = new HashMap<>();
    private final Map<UUID, Integer> disabledUntil = new HashMap<>();
    private final Map<String, ShieldShape> shapes = new HashMap<>();
    private final List<CutEffect> cuts = new ArrayList<>();
    private final Random random = new Random();
    private int tick;

    WallManager(AegisTitan plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
        this.widthKey = new NamespacedKey(plugin, "wall_width");
        this.heightKey = new NamespacedKey(plugin, "wall_height");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ sizes

    int minSize() {
        return Math.max(2, plugin.getConfig().getInt("shield.min-size", 3));
    }

    int maxWidth() {
        return plugin.getConfig().getInt("shield.max-width", 40);
    }

    int maxHeight() {
        return plugin.getConfig().getInt("shield.max-height", 24);
    }

    int[] getSize(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        int w = pdc.getOrDefault(widthKey, PersistentDataType.INTEGER, plugin.getConfig().getInt("shield.default-width", 14));
        int h = pdc.getOrDefault(heightKey, PersistentDataType.INTEGER, plugin.getConfig().getInt("shield.default-height", 10));
        return new int[]{clamp(w, minSize(), maxWidth()), clamp(h, minSize(), maxHeight())};
    }

    /** Saves the size and returns what was actually used after the limits. */
    int[] setSize(Player p, int w, int h) {
        int cw = clamp(w, minSize(), maxWidth());
        int ch = clamp(h, minSize(), maxHeight());
        p.getPersistentDataContainer().set(widthKey, PersistentDataType.INTEGER, cw);
        p.getPersistentDataContainer().set(heightKey, PersistentDataType.INTEGER, ch);
        Wall current = walls.get(p.getUniqueId());
        if (current != null && !current.retracting()) {
            // Rebuild it at the new size right away (plays the open animation again).
            Wall fresh = new Wall(p.getUniqueId(), shape(cw, ch));
            fresh.aim(p, distance());
            walls.put(p.getUniqueId(), fresh);
            onDeploy(p, fresh);
        }
        return new int[]{cw, ch};
    }

    private ShieldShape shape(int w, int h) {
        return shapes.computeIfAbsent(w + "x" + h, k -> new ShieldShape(w, h));
    }

    private double distance() {
        return plugin.getConfig().getDouble("shield.distance", 3.0);
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        tick++;
        int now = Bukkit.getCurrentTick();

        // Shattered shields counting down
        Iterator<Map.Entry<UUID, Integer>> dit = disabledUntil.entrySet().iterator();
        while (dit.hasNext()) {
            Map.Entry<UUID, Integer> entry = dit.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            int left = entry.getValue() - now;
            if (left <= 0) {
                dit.remove();
                if (p != null) {
                    p.sendActionBar(Component.text("\u2714 Aegis Wall Shield recharged!", NamedTextColor.AQUA));
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.4f);
                }
                continue;
            }
            if (p != null && tick % 10 == 0 && holdsShield(p)) {
                p.sendActionBar(Component.text("\u26A0 Shield shattered \u2014 back in " + ((left + 19) / 20) + "s",
                        NamedTextColor.RED));
            }
        }

        // Who is holding their shield up?
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean raising = p.hasActiveItem()
                    && items.isShield(p.getActiveItem())
                    && !disabledUntil.containsKey(id)
                    && p.getGameMode() != GameMode.SPECTATOR
                    && !p.isDead();
            Wall wall = walls.get(id);
            if (raising) {
                if (wall == null || wall.retracting()) {
                    int[] size = getSize(p);
                    wall = new Wall(id, shape(size[0], size[1]));
                    wall.aim(p, distance());
                    walls.put(id, wall);
                    onDeploy(p, wall);
                } else {
                    wall.aim(p, distance());
                }
            } else if (wall != null && !wall.retracting()) {
                wall.retractAge = 0;
                wall.world.playSound(wall.center.toLocation(wall.world), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.6f);
            }
        }

        Iterator<Wall> wit = walls.values().iterator();
        while (wit.hasNext()) {
            Wall w = wit.next();
            Player owner = Bukkit.getPlayer(w.owner);
            if (owner == null || !owner.getWorld().equals(w.world)) {
                wit.remove();
                continue;
            }
            render(w);
            if (w.solid()) {
                physics(w, owner);
            }
            w.age++;
            if (w.retracting()) {
                w.retractAge++;
                if (w.finished()) {
                    wit.remove();
                }
            }
        }

        cuts.removeIf(c -> !c.tick());
    }

    private boolean holdsShield(Player p) {
        return items.isShield(p.getInventory().getItemInMainHand()) || items.isShield(p.getInventory().getItemInOffHand());
    }

    // ------------------------------------------------------------------ visuals

    private void onDeploy(Player p, Wall w) {
        Location c = w.center.toLocation(w.world);
        w.world.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.7f);
        w.world.playSound(c, Sound.ITEM_TRIDENT_RETURN, 1f, 0.7f);
        w.world.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.6f);
        // Beam of light from the shield out to the wall
        Vector from = p.getEyeLocation().toVector().subtract(new Vector(0, 0.4, 0));
        Vector step = w.center.clone().subtract(from);
        double len = step.length();
        step.normalize().multiply(0.3);
        Vector pos = from.clone();
        for (double d = 0; d < len; d += 0.3) {
            Fx.spawn(w.world, Particle.ELECTRIC_SPARK, pos, 1, 0.02, 0);
            pos.add(step);
        }
        Fx.spawn(w.world, Particle.END_ROD, w.center, 25, 0.3, 0.15);
    }

    private void render(Wall w) {
        World world = w.world;
        ShieldShape sh = w.shape;
        double s = w.scale();
        if (s < 0.03) {
            return;
        }
        boolean animating = w.age < Wall.DEPLOY_TICKS || w.retracting();

        // Glowing rim
        if (animating || tick % 2 == 0) {
            double[] o = sh.outline;
            for (int i = 0; i < o.length; i += 2) {
                Fx.fade(world, w.point(o[i] * s, o[i + 1] * s, 0), OUTLINE);
            }
        }

        if (w.retracting()) {
            // Sparks sucked back in while it closes
            for (int i = 0; i < 14; i++) {
                double[] r = sh.randomInside(random);
                if (r != null) {
                    Vector p = w.point(r[0] * s, r[1] * s, 0);
                    Vector toCenter = w.center.clone().subtract(p);
                    Fx.move(world, Particle.ELECTRIC_SPARK, p, toCenter, 0.25);
                }
            }
            return;
        }

        // Energy lattice with a shimmering wave running through it
        if (tick % 2 == 1) {
            double[] lat = ((tick / 2) % 2 == 0) ? sh.latticeA : sh.latticeB;
            double phase = tick * 0.35;
            for (int i = 0; i < lat.length; i += 2) {
                double u = lat[i];
                double v = lat[i + 1];
                double wave = Math.sin(v * 0.8 + u * 0.25 - phase);
                if (wave < -0.6 && random.nextInt(3) == 0) {
                    continue;
                }
                Particle.DustOptions opt = wave > 0.75 ? LATTICE_BRIGHT : (wave > 0 ? LATTICE_MID : LATTICE_DIM);
                Fx.dust(world, w.point(u * s, v * s, 0), opt);
            }
        }

        // Scan line sweeping upward
        double sv = -sh.h / 2 + ((tick % 36) / 36.0) * sh.h;
        double hw = sh.halfWidth(sv);
        if (hw > 0) {
            for (double u = -hw; u <= hw; u += 0.7) {
                Fx.spawn(world, Particle.ELECTRIC_SPARK, w.point(u * s, sv * s, 0), 1, 0, 0);
            }
        }

        // Spinning emblem in the middle
        double er = sh.emblemR * s;
        double ev = sh.emblemV * s;
        double ang = tick * 0.12;
        for (int k = 0; k < 22; k++) {
            double a = ang + k * Math.PI * 2 / 22;
            Fx.dust(world, w.point(Math.cos(a) * er, ev + Math.sin(a) * er, 0), EMBLEM);
        }
        if (tick % 2 == 0) {
            for (int arm = 0; arm < 4; arm++) {
                double a = -ang * 1.5 + arm * Math.PI / 2;
                for (double d = 0.3; d < er * 0.8; d += 0.3) {
                    Fx.dust(world, w.point(Math.cos(a) * d, ev + Math.sin(a) * d, 0), EMBLEM_CORE);
                }
            }
        }

        // Twinkles
        for (int i = 0; i < 3; i++) {
            double[] r = sh.randomInside(random);
            if (r != null) {
                Fx.spawn(world, Particle.END_ROD, w.point(r[0] * s, r[1] * s, 0), 1, 0, 0);
            }
        }

        // Ripples where things hit it
        Iterator<double[]> rit = w.ripples.iterator();
        while (rit.hasNext()) {
            double[] rp = rit.next();
            double rr = 0.3 + rp[2] * 0.45;
            int n = (int) Math.max(10, rr * 7);
            for (int k = 0; k < n; k++) {
                double a = k * Math.PI * 2 / n;
                double u = rp[0] + Math.cos(a) * rr;
                double v = rp[1] + Math.sin(a) * rr;
                if (sh.contains(u, v)) {
                    Fx.dust(world, w.point(u * s, v * s, 0.05), RIPPLE);
                }
            }
            rp[2]++;
            if (rp[2] > 9) {
                rit.remove();
            }
        }

        if (w.age % 30 == 0) {
            world.playSound(w.center.toLocation(world), Sound.BLOCK_BEACON_AMBIENT, 0.7f, 1.3f);
        }
    }

    /** Sparks + ripple where something hit the wall. */
    void impact(Wall w, Vector hit) {
        double[] l = w.local(hit);
        double s = Math.max(0.15, w.scale());
        w.addRipple(l[0] / s, l[1] / s);
        Fx.spawn(w.world, Particle.ELECTRIC_SPARK, hit, 14, 0.25, 0.25);
        Fx.spawn(w.world, Particle.CRIT, hit, 10, 0.2, 0.4);
        Fx.spawn(w.world, Particle.END_ROD, hit, 4, 0.1, 0.08);
        Location loc = hit.toLocation(w.world);
        w.world.playSound(loc, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f + random.nextFloat() * 0.3f);
        w.world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.6f);
    }

    // ------------------------------------------------------------------ physics

    private void physics(Wall w, Player owner) {
        double s = w.scale();
        double rad = Math.max(w.shape.w, w.shape.h) * s / 2 + 3;
        boolean reflect = plugin.getConfig().getBoolean("shield.reflect-projectiles", true);
        Location c = w.center.toLocation(w.world);
        for (Entity e : w.world.getNearbyEntities(c, rad, rad, rad)) {
            if (e.equals(owner)) {
                continue;
            }
            if (e instanceof Projectile proj) {
                if (reflect) {
                    handleProjectile(w, owner, proj);
                }
            } else if (e instanceof LivingEntity le) {
                handleBody(w, le);
            }
        }
    }

    private void handleProjectile(Wall w, Player owner, Projectile proj) {
        if (proj.getShooter() instanceof Entity shooter && shooter.getUniqueId().equals(w.owner)) {
            return;
        }
        if (proj instanceof AbstractArrow arrow && arrow.isInBlock()) {
            return;
        }
        Vector pos = proj.getLocation().toVector();
        Vector vel = proj.getVelocity();
        if (vel.lengthSquared() < 1e-4) {
            return;
        }
        double[] a = w.local(pos);
        double[] b = w.local(pos.clone().add(vel));
        boolean crosses = (a[2] > 0) != (b[2] > 0) || Math.abs(b[2]) < 0.35;
        if (!crosses) {
            return;
        }
        double t = (a[2] == b[2]) ? 0 : a[2] / (a[2] - b[2]);
        t = Math.max(0, Math.min(1, t));
        double u = a[0] + (b[0] - a[0]) * t;
        double v = a[1] + (b[1] - a[1]) * t;
        if (!w.containsLocal(u, v)) {
            return;
        }
        double side = a[2] >= 0 ? 1 : -1;
        Vector hit = w.point(u, v, 0);
        Vector reflected = vel.clone().subtract(w.normal.clone().multiply(2 * vel.dot(w.normal))).multiply(0.7);
        proj.teleport(hit.clone().add(w.normal.clone().multiply(0.45 * side)).toLocation(w.world));
        proj.setVelocity(reflected);
        if (proj instanceof Fireball fireball && reflected.lengthSquared() > 1e-4) {
            fireball.setDirection(reflected.clone().normalize());
        }
        proj.setShooter(owner);
        impact(w, hit);
    }

    private void handleBody(Wall w, LivingEntity le) {
        if (le.isDead() || (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        double height = le.getHeight();
        Vector base = le.getLocation().toVector();
        double[] mid = w.local(base.clone().add(new Vector(0, height / 2, 0)));
        int curSide = mid[2] >= 0 ? 1 : -1;
        UUID id = le.getUniqueId();

        boolean overlap = false;
        double[] samples = {0.1, height / 2, Math.max(0.1, height - 0.1)};
        for (double fy : samples) {
            double[] l = w.local(base.clone().add(new Vector(0, fy, 0)));
            if (w.containsLocal(l[0], l[1])) {
                overlap = true;
                break;
            }
        }
        if (!overlap) {
            w.sides.put(id, curSide);
            return;
        }

        Integer prev = w.sides.get(id);
        int side = prev == null ? curSide : prev;
        double reach = le.getWidth() / 2 + 0.4;
        if (curSide == side && Math.abs(mid[2]) >= reach) {
            w.sides.put(id, curSide);
            return;
        }

        double shove = side * reach - mid[2];
        if (Math.abs(shove) > 2.5) {
            // The wall swung through them too fast to push back sensibly; just let it pass.
            w.sides.put(id, curSide);
            return;
        }
        if (!le.isInsideVehicle()) {
            le.teleport(le.getLocation().add(w.normal.clone().multiply(shove)));
        }
        Vector vel = le.getVelocity();
        double away = vel.dot(w.normal) * side;
        if (away < 0.2) {
            vel.add(w.normal.clone().multiply(side * (0.2 - away)));
            le.setVelocity(vel);
        }
        w.sides.put(id, side);

        int cd = w.bumpCooldown.getOrDefault(id, 0);
        if (w.age >= cd) {
            w.bumpCooldown.put(id, w.age + 10);
            Vector contact = w.point(mid[0], mid[1], 0);
            impact(w, contact);
        }
    }

    // ------------------------------------------------------------------ damage blocking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (walls.isEmpty()) {
            return;
        }
        Entity damager = event.getDamager();
        UUID attacker = damager.getUniqueId();
        Vector src;
        if (damager instanceof Projectile proj) {
            if (proj.getShooter() instanceof Entity shooter) {
                attacker = shooter.getUniqueId();
            }
            src = proj.getLocation().toVector().subtract(proj.getVelocity());
        } else if (damager instanceof LivingEntity living) {
            src = living.getEyeLocation().toVector();
        } else {
            src = damager.getLocation().toVector();
        }
        WallHit hit = blockingWall(event.getEntity(), src, attacker);
        if (hit != null) {
            event.setCancelled(true);
            impact(hit.wall(), hit.point());
        }
    }

    /** Returns the wall that stands between src and victim, or null. */
    WallHit blockingWall(Entity victim, Vector src, UUID attacker) {
        Vector target = victim.getLocation().toVector().add(new Vector(0, victim.getHeight() / 2, 0));
        for (Wall w : walls.values()) {
            if (!w.solid() || !w.world.equals(victim.getWorld()) || w.owner.equals(attacker)) {
                continue;
            }
            double[] a = w.local(src);
            double[] b = w.local(target);
            boolean isOwner = victim.getUniqueId().equals(w.owner);
            if (isOwner) {
                // The shield holder is safe from anything on the far side of their wall.
                if (a[2] <= 0) {
                    continue;
                }
            } else if ((a[2] > 0) == (b[2] > 0)) {
                continue;
            }
            double t = (a[2] == b[2]) ? 0 : a[2] / (a[2] - b[2]);
            t = Math.max(0, Math.min(1, t));
            double u = a[0] + (b[0] - a[0]) * t;
            double v = a[1] + (b[1] - a[1]) * t;
            if (!isOwner && !w.containsLocal(u, v)) {
                continue;
            }
            Vector point = w.nearestPoint(w.point(u, v, 0));
            return new WallHit(w, point, 0);
        }
        return null;
    }

    /** Stop normal axes from knocking the special shield down. */
    @EventHandler(ignoreCancelled = true)
    public void onShieldDisable(PlayerShieldDisableEvent event) {
        if (holdsShield(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ used by the Titan Cleaver

    /** First wall the ray hits (ignoring the caster's own wall). */
    WallHit rayCast(World world, Vector origin, Vector dir, double max, UUID exclude) {
        WallHit best = null;
        for (Wall w : walls.values()) {
            if (!w.solid() || w.owner.equals(exclude) || !w.world.equals(world)) {
                continue;
            }
            double denom = dir.dot(w.normal);
            if (Math.abs(denom) < 1e-4) {
                continue;
            }
            double t = w.center.clone().subtract(origin).dot(w.normal) / denom;
            if (t < 0 || t > max) {
                continue;
            }
            Vector hit = origin.clone().add(dir.clone().multiply(t));
            double[] l = w.local(hit);
            if (!w.containsLocal(l[0], l[1])) {
                continue;
            }
            if (best == null || t < best.distance()) {
                best = new WallHit(w, hit, t);
            }
        }
        return best;
    }

    /** Every open wall in a world except one player's own. */
    List<Wall> activeWalls(World world, UUID exclude) {
        List<Wall> out = new ArrayList<>();
        for (Wall w : walls.values()) {
            if (!w.retracting() && !w.owner.equals(exclude) && w.world.equals(world)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Walls close enough to an impact point to get cut. */
    List<Wall> wallsNear(World world, Vector p, double radius, UUID exclude) {
        List<Wall> out = new ArrayList<>();
        for (Wall w : walls.values()) {
            if (w.retracting() || w.owner.equals(exclude) || !w.world.equals(world)) {
                continue;
            }
            Player owner = Bukkit.getPlayer(w.owner);
            boolean near = w.nearestPoint(p).distance(p) <= radius
                    || (owner != null && owner.getLocation().toVector().distance(p) <= radius);
            if (near) {
                out.add(w);
            }
        }
        return out;
    }

    /** Cut a wall in half and knock the shield out for a while. */
    void shatter(Wall w, Vector impactPoint) {
        double[] l = w.local(impactPoint);
        double s = Math.max(0.15, w.scale());
        double cutU = Math.max(-w.shape.w * 0.3, Math.min(w.shape.w * 0.3, l[0] / s));
        cuts.add(new CutEffect(w, cutU, random));
        walls.remove(w.owner);

        int ticks = Math.max(1, plugin.getConfig().getInt("shield.disabled-seconds", 8)) * 20;
        disabledUntil.put(w.owner, Bukkit.getCurrentTick() + ticks);
        Player owner = Bukkit.getPlayer(w.owner);
        if (owner != null) {
            owner.setCooldown(Material.SHIELD, ticks);
            owner.clearActiveItem();
            owner.showTitle(Title.title(
                    Component.text("SHIELD SHATTERED", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("Your Aegis Wall was cleaved in half!", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }
    }

    boolean isDisabled(UUID id) {
        return disabledUntil.containsKey(id);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
