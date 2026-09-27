package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The Leviathan Trident: throw it, ride a colossal trident to your target, and obliterate the landing zone. */
final class TitanTrident implements Listener {

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;
    private final Terrain terrain;
    private final NamespacedKey scaleKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> riders = new HashSet<>();
    private final List<Ride> rides = new ArrayList<>();
    private final Random random = new Random();

    TitanTrident(AegisTitan plugin, Items items, WallManager walls, Terrain terrain) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
        this.terrain = terrain;
        this.scaleKey = new NamespacedKey(plugin, "trident_scale");
    }

    // ------------------------------------------------------------------ size / cooldown

    double maxScale() {
        return Math.max(1.0, plugin.getConfig().getDouble("trident.max-size", 30.0));
    }

    double getScale(Player p) {
        double s = p.getPersistentDataContainer().getOrDefault(scaleKey, PersistentDataType.DOUBLE, 1.0);
        return Math.max(1.0, Math.min(maxScale(), s));
    }

    double setScale(Player p, double scale) {
        double s = Math.max(1.0, Math.min(maxScale(), scale));
        p.getPersistentDataContainer().set(scaleKey, PersistentDataType.DOUBLE, s);
        return s;
    }

    int cooldownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("trident.cooldown-seconds", 10));
    }

    void setCooldownSeconds(int seconds) {
        int s = Math.max(0, seconds);
        plugin.getConfig().set("trident.cooldown-seconds", s);
        plugin.saveConfig();
        long latest = System.currentTimeMillis() + s * 1000L;
        cooldowns.replaceAll((id, ready) -> Math.min(ready, latest));
    }

    /** Clean up when the server stops mid-ride. */
    void shutdown() {
        for (Ride ride : new ArrayList<>(rides)) {
            ride.cleanup();
        }
        rides.clear();
        riders.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident) || !(trident.getShooter() instanceof Player player)) {
            return;
        }
        if (!items.isTrident(trident.getItemStack())) {
            return;
        }
        UUID id = player.getUniqueId();
        if (riders.contains(id)) {
            event.setCancelled(true);
            return;
        }
        if (player.isInsideVehicle()) {
            return; // just a normal throw
        }
        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(id, 0L);
        if (now < ready) {
            player.sendActionBar(Component.text(String.format("Leviathan Ride recharging\u2026 %.1fs  (normal throw)",
                    (ready - now) / 1000.0), NamedTextColor.AQUA));
            return; // normal loyalty throw while recharging
        }
        event.setCancelled(true); // the trident stays in your hand; we summon the big one instead
        cooldowns.put(id, now + cooldownSeconds() * 1000L);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        Ride ride = new Ride(player, getScale(player));
        rides.add(ride);
        ride.runTaskTimer(plugin, 0L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player p && riders.contains(p.getUniqueId())) {
            event.setCancelled(true); // no jumping off mid-flight
        }
    }

    // ================================================================== the ride

    private final class Ride extends BukkitRunnable {

        private static final Vector UP = new Vector(0, 1, 0);

        // --- size
        private final double k;
        private final double shaftLen;
        private final double shaftR;
        private final double prongLen;
        private final double sideLen;
        private final double headHalfWidth;
        private final double crossDip;
        private final double prongWidth;
        private final double g;
        private final double totalLen;
        private final double seatFromTip;
        private final double step;
        private final float volume;
        private final float pitch;

        private final Particle.DustOptions shaftDark;
        private final Particle.DustOptions shaftLight;
        private final Particle.DustOptions gold;
        private final Particle.DustOptions prong;
        private final Particle.DustOptions prongEdge;
        private final Particle.DustTransition gem;
        private final Particle.DustTransition tipGlow;
        private final Particle.DustOptions water;

        // --- timeline
        private final int summonTicks;
        private final int flightTicks;
        private static final int LINGER = 44;

        // --- path (the tip follows a curve from start to target)
        private final UUID riderId;
        private final World world;
        // The rider's path (a curve from where you stood to just above/behind the target)
        private Vector p0;
        private Vector p1;
        private Vector p2;
        private final Vector target;     // where the centre prong will hit
        private final Vector heading;    // flat direction you threw in
        private final double aimPitch;
        private final double arcBoost;
        private double launchPitch;
        private double dive; // how steep it comes down
        private final Vector startDir;
        private final Vector seat0;
        private Vector flightDir0;
        private Vector seatPos;
        private ItemDisplay vehicle;
        private boolean waitingForGround;
        private boolean underwater;
        private int holdTicks;

        // --- live state
        private int t;
        private Vector tip;
        private Vector axis;
        private Vector prevTip;
        private int endedAt = -1;
        private boolean clashed;
        private Vector endPoint;
        private int groundRings;
        private final Set<UUID> struck = new HashSet<>();

        // --- landing
        private Terrain.Scar scar;
        private final List<Hole> holes = new ArrayList<>();
        private final List<Vector> holeTops = new ArrayList<>();
        private BlockData groundData;
        private ItemStack chip;
        private double waveRadius;
        private double waveSpeed;
        private final Set<UUID> waveHit = new HashSet<>();

        Ride(Player player, double scale) {
            FileConfiguration cfg = plugin.getConfig();
            this.k = scale;
            this.g = Math.pow(k, 0.8);
            this.shaftLen = 14 * g;
            this.shaftR = 0.28 * Math.pow(k, 0.6);
            this.prongLen = 5.2 * g;
            this.sideLen = 4.0 * g;
            this.headHalfWidth = 3.6 * g;
            this.crossDip = 0.9 * g;
            this.prongWidth = 0.45 * g;
            this.totalLen = shaftLen + prongLen;
            this.seatFromTip = shaftLen * 0.6 + prongLen;
            // keep the particle count sane on gigantic tridents
            this.step = Math.max(0.3 * Math.pow(k, 0.5), (shaftLen + prongLen) / 160.0);
            this.volume = (float) (2.0 * Math.pow(k, 0.7));
            this.pitch = (float) (1.0 / Math.pow(k, 0.15));

            float size = (float) Math.min(4.0, 1.8 * Math.pow(k, 0.4));
            this.shaftDark = Fx.dust(0x1E5A57, size);
            this.shaftLight = Fx.dust(0x3C8F86, size);
            this.gold = Fx.dust(0x9FEFE4, size);
            this.prong = Fx.dust(0x7FE3D6, size);
            this.prongEdge = Fx.dust(0xD8FFF8, size * 0.9f);
            this.gem = Fx.fade(0x3FE0FF, 0xFFFFFF, Math.min(4f, size * 1.4f));
            this.tipGlow = Fx.fade(0xFFFFFF, 0x7FF7FF, size);
            this.water = Fx.dust(0x9BE7FF, size * 0.7f);

            this.riderId = player.getUniqueId();
            this.world = player.getWorld();
            this.summonTicks = 12 + (int) Math.round(Math.min(k, 20));

            // ---------- where are we going?
            Location eye = player.getEyeLocation();
            Vector origin = eye.toVector();
            Vector dir = eye.getDirection().normalize();
            double range = Math.min(600, cfg.getDouble("trident.flight-range", 250) * Math.pow(k, 0.3));
            Vector target = null;
            boolean needGround = false;
            // Creatures in the line of sight (only looks at loaded chunks)
            RayTraceResult ent = world.rayTraceEntities(eye, dir, Math.min(range, 160), 0.6,
                    e -> e instanceof LivingEntity && !e.getUniqueId().equals(riderId));
            double entDist = ent != null ? ent.getHitPosition().distance(origin) : Double.MAX_VALUE;
            // Blocks: march along the aim, stopping at the edge of the loaded world (no freezing!)
            Vector marchEnd = origin.clone().add(dir.clone().multiply(range));
            for (double d = 0.5; d <= range; d += 0.5) {
                Vector q = origin.clone().add(dir.clone().multiply(d));
                if (d >= entDist) {
                    break;
                }
                if (!world.isChunkLoaded(q.getBlockX() >> 4, q.getBlockZ() >> 4)) {
                    marchEnd = q;
                    needGround = true;
                    break;
                }
                if (q.getY() > world.getMinHeight() && q.getY() < world.getMaxHeight()
                        && world.getBlockAt(q.getBlockX(), q.getBlockY(), q.getBlockZ()).getType().isSolid()) {
                    target = q.clone().subtract(dir.clone().multiply(0.4));
                    break;
                }
            }
            if (target == null && ent != null) {
                target = ent.getHitEntity().getLocation().toVector();
            }
            if (target == null) {
                if (!needGround && world.isChunkLoaded(marchEnd.getBlockX() >> 4, marchEnd.getBlockZ() >> 4)) {
                    target = surfaceAt(marchEnd);
                } else {
                    // Far away: guess the height now, fix it when the chunk has loaded in the background
                    target = new Vector(marchEnd.getX(), origin.getY() - 1, marchEnd.getZ());
                    needGround = true;
                }
            }
            this.waitingForGround = needGround;
            // Which way (flat) are we heading?
            Vector flatAim = dir.clone().setY(0);
            if (flatAim.lengthSquared() < 1e-4) {
                double yaw = Math.toRadians(eye.getYaw());
                flatAim = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
            }
            flatAim.normalize();
            Vector toTarget = target.clone().subtract(origin).setY(0);
            this.heading = toTarget.lengthSquared() > 1 ? toTarget.clone().normalize() : flatAim.clone();

            // The trident needs some room: the target must be further than the trident is long
            double minDist = seatFromTip * Math.cos(Math.toRadians(55)) + 14;
            double flatDist = toTarget.length();
            if (flatDist < minDist) {
                Vector pushed = origin.clone().add(heading.clone().multiply(minDist));
                pushed.setY(target.getY() + 2);
                target = world.isChunkLoaded(pushed.getBlockX() >> 4, pushed.getBlockZ() >> 4)
                        ? groundBelow(pushed) : pushed;
            }
            this.target = target;

            // Launch angle = the angle you threw at (a bit more so it always takes off), between 12 and 75 degrees
            this.arcBoost = Math.toRadians(10 * cfg.getDouble("trident.arc-height", 1.0));
            this.aimPitch = Math.asin(Math.max(-1, Math.min(1, dir.getY())));

            Vector a0 = heading.clone().multiply(Math.cos(Math.max(-0.3, aimPitch))).add(new Vector(0, Math.sin(Math.max(-0.3, aimPitch)), 0));
            a0.normalize();
            this.startDir = a0;
            this.seat0 = player.getLocation().toVector().add(new Vector(0, 0.6, 0));
            this.seatPos = seat0.clone();
            buildPath();
            double len = 0;
            Vector last = p0.clone();
            for (int i = 1; i <= 24; i++) {
                Vector p = bezier(i / 24.0);
                len += p.distance(last);
                last = p;
            }
            double speed = Math.min(4.5, cfg.getDouble("trident.flight-speed", 3.0) * Math.pow(k, 0.25));
            this.flightTicks = (int) Math.max(20, Math.min(400, Math.round(len / speed)));
            // Load the landing area in the background so the landing doesn't hitch
            int cx0 = target.getBlockX() >> 4;
            int cz0 = target.getBlockZ() >> 4;
            int cr = (int) Math.min(3, Math.ceil((3.6 * g * 2 + 20) / 16.0));
            for (int cx = cx0 - cr; cx <= cx0 + cr; cx++) {
                for (int cz = cz0 - cr; cz <= cz0 + cr; cz++) {
                    if (!world.isChunkLoaded(cx, cz)) {
                        world.getChunkAtAsync(cx, cz);
                    }
                }
            }
            if (waitingForGround) {
                int bx = target.getBlockX();
                int bz = target.getBlockZ();
                world.getChunkAtAsyncUrgently(this.target.toLocation(world)).thenAccept(chunk -> {
                    int y = world.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.OCEAN_FLOOR);
                    this.target.setY(y + 1);
                    buildPath();
                    waitingForGround = false;
                });
            }

            this.axis = a0.clone();
            this.tip = seatPos.clone().add(axis.clone().multiply(seatFromTip));
            this.prevTip = tip.clone();

            // ---------- climb aboard
            Location seatLoc = seatWorld().toLocation(world, player.getLocation().getYaw(), player.getLocation().getPitch());
            this.vehicle = world.spawn(seatLoc, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(2);
            });
            vehicle.addPassenger(player);
            riders.add(riderId);
        }

        private Vector groundBelow(Vector target) {
            int x = (int) Math.floor(target.getX());
            int z = (int) Math.floor(target.getZ());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return target.clone();
            }
            int y = (int) Math.floor(target.getY());
            for (int i = 0; i < 400 && y > world.getMinHeight(); i++) {
                if (world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    break;
                }
                y--;
            }
            return new Vector(target.getX(), y, target.getZ());
        }

        /** Ground level at a far-away point (uses the height map, so it's fast). */
        private Vector surfaceAt(Vector p) {
            int x = (int) Math.floor(p.getX());
            int z = (int) Math.floor(p.getZ());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return p.clone();
            }
            int y = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
            if (p.getY() < y) {
                return groundBelow(p); // aimed under an overhang or into a cave
            }
            return new Vector(p.getX(), y + 1, p.getZ());
        }

        /**
         * Builds the rider's curve: it leaves at the launch angle in the direction you threw,
         * and comes down at a steep dive so the prongs drive into the target.
         */
        private void buildPath() {
            // Dive at least 55 degrees; steeper if the target is way below us (off a cliff, into a canyon)
            Vector relT = target.clone().subtract(seat0);
            double hT = Math.max(1, relT.getX() * heading.getX() + relT.getZ() * heading.getZ());
            dive = Math.max(Math.toRadians(55), Math.min(Math.toRadians(80), Math.atan2(-relT.getY(), hT) + Math.toRadians(25)));
            Vector down = new Vector(0, -1, 0);
            Vector dEnd = heading.clone().multiply(Math.cos(dive)).add(down.clone().multiply(Math.sin(dive)));
            Vector end = target.clone().subtract(dEnd.clone().multiply(seatFromTip));

            Vector rel = end.clone().subtract(seat0);
            double hE = rel.getX() * heading.getX() + rel.getZ() * heading.getZ();
            double yE = rel.getY();
            // Launch at your throw angle, but always steep enough to clear the curve (12 to 80 degrees)
            double chord = Math.atan2(yE, Math.max(0.1, hE));
            launchPitch = Math.min(Math.toRadians(80),
                    Math.max(Math.max(Math.toRadians(12), aimPitch + arcBoost), chord + Math.toRadians(12)));
            Vector dStart = heading.clone().multiply(Math.cos(launchPitch)).add(new Vector(0, Math.sin(launchPitch), 0));

            // Where the launch line and the dive line cross = the curve's control point
            double denom = Math.sin(launchPitch + dive);
            double a = (hE * Math.sin(dive) + yE * Math.cos(dive)) / denom;
            double b = (Math.sin(launchPitch) * hE - Math.cos(launchPitch) * yE) / denom;
            Vector control;
            if (a > 1 && b > 1) {
                control = seat0.clone().add(dStart.clone().multiply(a));
            } else {
                // target is very high up or very close: just arc up and over
                double span = end.distance(seat0);
                control = seat0.clone().add(end).multiply(0.5).add(new Vector(0, Math.max(8, span * 0.35), 0));
            }
            control.setY(Math.min(control.getY(), world.getMaxHeight() + 150));
            this.p0 = seat0.clone();
            this.p1 = control;
            this.p2 = end;
            this.flightDir0 = p1.clone().subtract(p0);
            if (flightDir0.lengthSquared() < 1e-6) {
                flightDir0 = dStart.clone();
            }
            flightDir0.normalize();
        }

        private Vector bezier(double u) {
            double a = (1 - u) * (1 - u);
            double b = 2 * (1 - u) * u;
            double c = u * u;
            return new Vector(
                    a * p0.getX() + b * p1.getX() + c * p2.getX(),
                    a * p0.getY() + b * p1.getY() + c * p2.getY(),
                    a * p0.getZ() + b * p1.getZ() + c * p2.getZ());
        }

        private Vector bezierDir(double u) {
            Vector d = p1.clone().subtract(p0).multiply(2 * (1 - u)).add(p2.clone().subtract(p1).multiply(2 * u));
            return d.lengthSquared() < 1e-6 ? axis.clone() : d.normalize();
        }

        /** Where the rider sits: on top of the shaft. */
        private Vector seatWorld() {
            return seatPos.clone().add(new Vector(0, shaftR + 0.15, 0));
        }

        // ------------------------------------------------------------ main loop

        @Override
        public void run() {
            Player rider = Bukkit.getPlayer(riderId);
            if (endedAt < 0 && (rider == null || !rider.isValid() || !rider.getWorld().equals(world))) {
                cleanup();
                return;
            }

            if (endedAt < 0) {
                if (t < summonTicks) {
                    summonFrame(rider);
                } else {
                    flightFrame(rider);
                }
            } else {
                int since = t - endedAt;
                if (clashed) {
                    clashAftermath(since);
                    if (since > 26) {
                        finish();
                        return;
                    }
                } else {
                    landedAftermath(rider, since);
                    if (since > LINGER && since * waveSpeed > waveRadius + 1 && holes.isEmpty()) {
                        finish();
                        return;
                    }
                }
            }
            t++;
        }

        private void summonFrame(Player rider) {
            double p = t / (double) summonTicks;
            // Swing round to point along the launch path while it forms
            double ease = p * p * (3 - 2 * p);
            axis = startDir.clone().multiply(1 - ease).add(flightDir0.clone().multiply(ease));
            if (axis.lengthSquared() < 1e-6) {
                axis = flightDir0.clone();
            }
            axis.normalize();
            seatPos = seat0.clone();
            tip = seatPos.clone().add(axis.clone().multiply(seatFromTip));
            moveVehicle(rider);
            drawTrident(Math.min(1.0, 0.2 + p), 0);

            Vector center = tip.clone().subtract(axis.clone().multiply(totalLen * 0.5));
            for (int i = 0; i < 8; i++) {
                double ang = t * 0.5 + i * Math.PI / 4;
                double r = (4.0 * (1 - p) + 1.0) * Math.sqrt(k);
                Vector from = center.clone().add(new Vector(Math.cos(ang) * r, (random.nextDouble() - 0.5) * 2 * k, Math.sin(ang) * r));
                Fx.move(world, Particle.SPLASH, from, center.clone().subtract(from), 0.3);
                Fx.move(world, Particle.NAUTILUS, from, center.clone().subtract(from), 0.1);
            }
            Fx.spawn(world, Particle.GLOW, center, (int) (6 * Math.sqrt(k)), 1.5 * k, 0.05);
            if (t == 0) {
                Location loc = center.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume, 0.8f * pitch);
                world.playSound(loc, Sound.BLOCK_CONDUIT_ACTIVATE, volume, 0.7f * pitch);
                world.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, volume * 0.6f, 0.6f);
            }
            if (t == summonTicks - 1) {
                Location loc = center.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_3, volume, 0.7f * pitch);
                world.playSound(loc, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, volume, 0.6f);
                Fx.spawn(world, Particle.SPLASH, center, (int) (80 * Math.sqrt(k)), 1.5 * k, 0.5);
                Fx.spawn(world, Particle.CLOUD, center, (int) (20 * Math.sqrt(k)), k, 0.1);
            }
        }

        private void flightFrame(Player rider) {
            int ft = t - summonTicks + 1 - holdTicks;
            double p = Math.min(1.0, ft / (double) flightTicks);
            double u = 0.55 * p + 0.45 * p * p; // speeds up as it dives
            prevTip = tip.clone();
            seatPos = bezier(u);
            axis = bezierDir(u);
            tip = seatPos.clone().add(axis.clone().multiply(seatFromTip));
            Vector move = tip.clone().subtract(prevTip);
            double moved = move.length();

            // 1) Did it smash into an Aegis Wall?
            if (moved > 1e-4) {
                WallManager.WallHit hit = walls.rayCast(world, prevTip, move.clone().normalize(), moved + 0.2, riderId);
                if (hit != null) {
                    tip = hit.point().clone();
                    clash(rider, hit);
                    return;
                }
            }
            // 2) Did it hit the ground? (it punches through anything on the climb, lands on the way down)
            int steps = (axis.getY() < -0.05 && p > 0.75) ? (int) Math.ceil(moved / 0.5) + 1 : 0;
            for (int i = 1; i <= steps; i++) {
                Vector q = prevTip.clone().add(move.clone().multiply(i / (double) steps));
                if (!world.isChunkLoaded(q.getBlockX() >> 4, q.getBlockZ() >> 4)) {
                    continue; // never force-load chunks mid-flight (that's what caused freezes)
                }
                if (q.getY() < world.getMinHeight()
                        || isGround(world.getBlockAt(q.getBlockX(), q.getBlockY(), q.getBlockZ()))) {
                    tip = q;
                    land(rider);
                    return;
                }
            }
            if (p >= 1.0) {
                tip = target.clone();
                land(rider);
                return;
            }
            if (waitingForGround && p > 0.6) {
                // hover at this point of the arc for a moment until we know where the far ground is
                holdTicks++;
                if (holdTicks > 60) {
                    waitingForGround = false;
                }
            }

            moveVehicle(rider);
            if (k <= 6 || t % 2 == 0) {
                drawTrident(1.0, t * 0.25);
            }
            trail(rider);
            ramEntities(rider);
            if (ft % 8 == 0) {
                Location loc = tip.toLocation(world);
                world.playSound(loc, Sound.ITEM_TRIDENT_RIPTIDE_1, volume * 0.7f, 0.6f * pitch);
            }
        }

        private void moveVehicle(Player rider) {
            if (vehicle == null || !vehicle.isValid()) {
                return;
            }
            Location loc = seatWorld().toLocation(world, rider.getLocation().getYaw(), rider.getLocation().getPitch());
            vehicle.teleport(loc); // since 1.21.10 passengers stay on by default
            rider.setFallDistance(0);
        }

        private void trail(Player rider) {
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            Fx.spawn(world, Particle.CLOUD, butt, (int) Math.ceil(2 * Math.sqrt(k)), 0.3 * k, 0.02);
            Fx.spawn(world, Particle.SPLASH, butt, (int) (12 * Math.sqrt(k)), 0.4 * k, 0.2);
            Fx.spawn(world, Particle.ELECTRIC_SPARK, tip, 3, 0.2 * k, 0.1);
            // Spiral of water wrapping the shaft
            for (int i = 0; i < 10; i++) {
                double s = random.nextDouble() * totalLen;
                double ang = s * 0.9 + t * 0.6;
                Vector[] f = frame();
                double r = shaftR * 3 + 0.3 * k;
                Vector p = tip.clone().subtract(axis.clone().multiply(totalLen - s))
                        .add(f[0].clone().multiply(Math.cos(ang) * r)).add(f[1].clone().multiply(Math.sin(ang) * r));
                Fx.dust(world, p, water);
                if (i % 3 == 0) {
                    Fx.spawn(world, Particle.FALLING_WATER, p, 1, 0.05, 0);
                }
            }
        }

        /** Creatures the head plows through get swatted aside. */
        private void ramEntities(Player rider) {
            double dmg = plugin.getConfig().getDouble("trident.ram-damage", 10);
            double reach = headHalfWidth + 1.0;
            for (Entity e : world.getNearbyEntities(tip.toLocation(world), reach, reach, reach)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || struck.contains(e.getUniqueId())
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                struck.add(e.getUniqueId());
                if (dmg > 0) {
                    le.damage(dmg, rider);
                }
                Vector side = le.getLocation().toVector().subtract(tip).setY(0);
                if (side.lengthSquared() < 1e-4) {
                    side = new Vector(1, 0, 0);
                }
                le.setVelocity(side.normalize().multiply(1.2).add(axis.clone().multiply(0.8)).setY(0.6));
                Fx.spawn(world, Particle.CRIT, le.getLocation().toVector().add(new Vector(0, 1, 0)), 15, 0.4, 0.4);
            }
        }

        // ------------------------------------------------------------ landing

        private void land(Player rider) {
            endedAt = t;
            // come in steep so the prongs drive down into the ground
            if (axis.getY() > -0.6) {
                axis.setY(-0.6);
                axis.normalize();
            }
            sinkFrom = tip.clone();
            // Keep going (through water, leaves, air...) along the trident until it meets real ground
            for (int i = 0; i < 128 && tip.getY() > world.getMinHeight(); i++) {
                if (!world.isChunkLoaded(tip.getBlockX() >> 4, tip.getBlockZ() >> 4)) {
                    break;
                }
                if (isGround(world.getBlockAt(tip.getBlockX(), tip.getBlockY(), tip.getBlockZ()))) {
                    break;
                }
                tip.add(axis.clone().multiply(0.8));
            }
            double travel = sinkFrom.distance(tip) + sinkDepth();
            sinkTicks = (int) Math.max(8, Math.min(30, 6 + travel / 3.0));
            underwater = world.getBlockAt(tip.getBlockX(), tip.getBlockY() + 1, tip.getBlockZ()).getType() == Material.WATER;
            endPoint = tip.clone();

            FileConfiguration cfg = plugin.getConfig();
            waveRadius = Math.min(cfg.getDouble("trident.max-shockwave-radius", 90),
                    cfg.getDouble("trident.shockwave-radius", 12) * Math.pow(k, 0.85));
            waveSpeed = waveRadius / (12.0 + 2.0 * k);

            Block ground = Terrain.surface(world, tip.getX(), tip.getZ(), tip.getY());
            groundData = ground != null ? ground.getBlockData() : Material.STONE.createBlockData();
            Material chipType = groundData.getMaterial();
            chip = new ItemStack(chipType.isItem() && !chipType.isAir() ? chipType : Material.COBBLESTONE);

            Location loc = tip.toLocation(world);
            world.strikeLightningEffect(loc);
            if (k >= 3) {
                for (int i = 0; i < 3; i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double r = waveRadius * (0.3 + random.nextDouble() * 0.4);
                    Vector p = groundBelow(tip.clone().add(new Vector(Math.cos(ang) * r, 4, Math.sin(ang) * r)));
                    world.strikeLightningEffect(p.toLocation(world));
                }
            }
            world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ITEM_TRIDENT_HIT_GROUND, volume * 1.5f, 0.5f);
            world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, volume, 0.8f * pitch);
            world.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, volume, 0.5f);

            Fx.spawn(world, Particle.EXPLOSION_EMITTER, tip, (int) Math.min(4, Math.ceil(k / 2)), 0.8 * k, 0);
            Fx.spawn(world, Particle.SONIC_BOOM, tip, (int) Math.min(4, Math.ceil(k / 2)), 0.5 * k, 0);
            Fx.spawn(world, Particle.BLOCK, tip, (int) Math.min(400, 150 * Math.sqrt(k)), 1.5 * k, 0.4 * k, 1.5 * k, 0.4, groundData);
            Fx.spawn(world, Particle.FLASH, tip, 1, 0, 0, 0, 0, org.bukkit.Color.fromRGB(0xFFD966));
            colorBurst(tip, 1.0);
            groundRings = 0;

            // Execute everything in the direct hit zone
            killZone(rider);

            // Three huge holes in a row where the prongs punched in
            if (cfg.getBoolean("trident.holes", true)) {
                scar = terrain.newScar(true); // also breaks the ground under water
                planHoles();
            }
            if (underwater) {
                world.playSound(loc, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, volume * 1.5f, 0.5f);
                Fx.spawn(world, Particle.BUBBLE_COLUMN_UP, tip, (int) Math.min(300, 120 * Math.sqrt(k)), k, 1.5 * k, k, 0.3, null);
            }

            // The sink animation carries it from where it touched down, into the ground.
            // The rider stays on board the whole time.
            tip = sinkFrom.clone();
            if (rider != null) {
                rider.setFallDistance(0);
            }
        }

        private void killZone(Player rider) {
            double kill = plugin.getConfig().getDouble("trident.kill-damage", 60);
            double zone = holeSpacing() + holeRadius() + 2;
            double yRange = 4 * Math.sqrt(k);
            for (Entity e : world.getNearbyEntities(tip.toLocation(world), zone, yRange, zone)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || le.isDead() || isImmune(le)) {
                    continue;
                }
                WallManager.WallHit block = walls.blockingWall(le, tip.clone().add(new Vector(0, 1, 0)), riderId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }
                waveHit.add(e.getUniqueId());
                if (le.getHealth() > 1.0) {
                    le.setHealth(1.0);
                }
                le.setNoDamageTicks(0);
                if (rider != null) {
                    le.damage(kill, rider);
                } else {
                    le.damage(kill);
                }
                Fx.spawn(world, Particle.DAMAGE_INDICATOR, le.getLocation().toVector().add(new Vector(0, 1, 0)), 20, 0.4, 0.2);
            }
        }

        private double holeRadius() {
            return Math.min(plugin.getConfig().getDouble("trident.hole-max-radius", 8), 1.3 * g);
        }

        private double holeDepth() {
            return Math.min(plugin.getConfig().getDouble("trident.hole-max-depth", 70), 16 + 10 * g);
        }

        /** Distance between the holes = where the side prongs actually are, so each prong sits in its hole. */
        private double holeSpacing() {
            return headHalfWidth * 0.88;
        }

        /** One hole being drilled down, a layer at a time. */
        private final class Hole {
            final double topX;
            final double topZ;
            final int topY;
            final Vector down;
            final double radius;
            final int layers;
            int layer;

            Hole(double x, double z, int topY, Vector down, double radius, double depth) {
                this.topX = x;
                this.topZ = z;
                this.topY = topY;
                this.down = down;
                this.radius = radius;
                this.layers = (int) Math.ceil(depth);
                this.layer = -(int) Math.ceil(radius * 0.4); // also clear the lip just above ground
            }

            boolean done() {
                return layer > layers;
            }

            /** Drill one layer; returns roughly how many blocks were looked at. */
            int dig() {
                int y = topY - layer;
                double f = Math.max(0, layer) / Math.max(0.3, -down.getY());
                double cx = topX + down.getX() * f;
                double cz = topZ + down.getZ() * f;
                double r = radius * (layer > layers * 0.85 ? 0.75 : 1.0); // narrower at the bottom, like a punch
                int checked = 0;
                if (y > world.getMinHeight() && y < world.getMaxHeight()) {
                    int minX = (int) Math.floor(cx - r);
                    int maxX = (int) Math.floor(cx + r);
                    int minZ = (int) Math.floor(cz - r);
                    int maxZ = (int) Math.floor(cz + r);
                    BlockData shown = null;
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            double dx = x + 0.5 - cx;
                            double dz = z + 0.5 - cz;
                            if (dx * dx + dz * dz > r * r || !world.isChunkLoaded(x >> 4, z >> 4)) {
                                continue;
                            }
                            checked++;
                            Block b = world.getBlockAt(x, y, z);
                            BlockData data = b.getBlockData();
                            if (scar.cut(b) && shown == null) {
                                shown = data;
                            }
                        }
                    }
                    if (shown != null && layer % 2 == 0) {
                        // debris blasting up out of the hole
                        Vector mouth = new Vector(topX, topY + 1.2, topZ);
                        Fx.spawn(world, Particle.BLOCK, mouth, (int) (6 * Math.sqrt(k)), r * 0.5, 0.4, r * 0.5, 0.3, shown);
                        Fx.move(world, underwater ? Particle.BUBBLE_COLUMN_UP : Particle.CLOUD, mouth,
                                new Vector(0, 1, 0), 0.2 * Math.sqrt(k));
                    }
                }
                layer++;
                return Math.max(1, checked);
            }
        }

        /** Three huge holes in a row across the landing, drilled along the angle it came in at. */
        private void planHoles() {
            Vector side = frame()[0];
            Vector down = axis.clone();
            if (down.getY() > -0.6) {
                down.setY(-0.6); // holes always go down into the ground
            }
            down.normalize();
            double r = holeRadius();
            double depth = holeDepth();
            double spacing = holeSpacing();
            for (int i = -1; i <= 1; i++) {
                double x = tip.getX() + side.getX() * spacing * i;
                double z = tip.getZ() + side.getZ() * spacing * i;
                int topY = topAt(x, z, tip.getY());
                double hr = r;
                holes.add(new Hole(x, z, topY, down, hr, depth * (i == 0 ? 1.0 : 0.85)));
                holeTops.add(new Vector(x, topY + 1, z));
            }
            // Fracture lines radiating out from the impact, like the ground split open
            int rays = 9;
            double rayLen = Math.min(60, holeSpacing() * 2.2 + r * 3);
            for (int i = 0; i < rays; i++) {
                double ang = i * Math.PI * 2 / rays + random.nextDouble() * 0.5;
                double x = tip.getX();
                double z = tip.getZ();
                for (double d = 0; d < rayLen; d += 0.8) {
                    ang += (random.nextDouble() - 0.5) * 0.35; // jagged
                    x += Math.cos(ang) * 0.8;
                    z += Math.sin(ang) * 0.8;
                    Block b = Terrain.surface(world, x, z, tip.getY());
                    if (b != null && scar.crack(b, random) && d < rayLen * 0.4 && random.nextDouble() < 0.25) {
                        scar.raise(b, random);
                    }
                    if (random.nextDouble() < 0.04) {
                        // little side branch
                        double ba = ang + (random.nextBoolean() ? 0.9 : -0.9);
                        double bx = x;
                        double bz = z;
                        for (int j = 0; j < 5; j++) {
                            bx += Math.cos(ba) * 0.8;
                            bz += Math.sin(ba) * 0.8;
                            Block bb = Terrain.surface(world, bx, bz, tip.getY());
                            if (bb != null) {
                                scar.crack(bb, random);
                            }
                        }
                    }
                }
            }
            // Cracked, heaved-up ground all around the holes
            for (Vector top : holeTops) {
                int cracks = (int) Math.min(400, 30 * Math.sqrt(k) + r * 6);
                for (int i = 0; i < cracks; i++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double d = r + 0.5 + random.nextDouble() * r * 1.6;
                    Block b = Terrain.surface(world, top.getX() + Math.cos(ang) * d, top.getZ() + Math.sin(ang) * d, top.getY());
                    if (b != null && scar.crack(b, random) && random.nextDouble() < 0.35) {
                        scar.raise(b, random);
                    }
                }
            }
        }

        /** Y of the top solid block near (x, z), searching around a height. */
        private int topAt(double x, double z, double nearY) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                return (int) Math.floor(nearY) - 1;
            }
            int start = (int) Math.floor(nearY) + 4;
            for (int y = start; y > start - 24 && y > world.getMinHeight(); y--) {
                if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                    return y;
                }
            }
            return (int) Math.floor(nearY) - 1;
        }

        private void digHoles(int since) {
            if (scar == null || holes.isEmpty()) {
                return;
            }
            int budget = Math.max(200, plugin.getConfig().getInt("axe.blocks-per-tick", 6000));
            // all three holes sink together
            while (budget > 0 && !holes.isEmpty()) {
                for (Hole h : holes) {
                    if (!h.done() && budget > 0) {
                        budget -= h.dig();
                    }
                }
                holes.removeIf(Hole::done);
            }
            if (since % 5 == 0 && !holes.isEmpty()) {
                for (Vector top : holeTops) {
                    world.playSound(top.toLocation(world), Sound.BLOCK_DEEPSLATE_BREAK, volume * 0.7f, 0.5f);
                }
            }
        }

        private void landedAftermath(Player rider, int since) {
            // 1) Drive all the way down into the ground (head buried, shaft sticking out) with you still on it
            if (since <= sinkTicks) {
                double f = since / (double) sinkTicks;
                double ease = 1 - (1 - f) * (1 - f);
                Vector sinkTo = endPoint.clone().add(axis.clone().multiply(sinkDepth()));
                tip = sinkFrom.clone().multiply(1 - ease).add(sinkTo.clone().multiply(ease));
                if (since % 2 == 0) {
                    Fx.spawn(world, Particle.BLOCK, endPoint, (int) Math.min(120, 40 * Math.sqrt(k)), 0.8 * k, 0.3, 0.8 * k, 0.3, groundData);
                }
            }
            // keep the rider sitting on the shaft
            if (vehicle != null) {
                seatPos = tip.clone().subtract(axis.clone().multiply(seatFromTip));
                if (rider != null && rider.isValid()) {
                    moveVehicle(rider);
                }
                // 2) Once it has fully planted and the blast is over, hop off right in front of it
                if (since >= releaseTick()) {
                    dismount(rider, false);
                }
            }
            // 2) Stays planted, glowing, then comes apart into rising light
            boolean dissolving = since > LINGER - 14;
            if (since <= LINGER && (k <= 6 || since % 2 == 0)) {
                double vis = dissolving ? 1.0 - (since - (LINGER - 14)) / 14.0 : 1.0;
                drawTrident(vis, 0);
                if (dissolving) {
                    riseFromTrident(vis);
                } else if (since % 3 == 0) {
                    spiralAroundShaft(since);
                }
            }
            // 3) The coloured blast keeps going for a moment
            if (since == 2 || since == 4 || since == 7) {
                colorBurst(endPoint, 0.45);
            }
            if (since <= 12 && since % 3 == 0) {
                double rr = (2 + since * 1.2) * Math.sqrt(k);
                ring(endPoint.clone().add(new Vector(0, 0.3, 0)), rr, (since / 3) % 3 == 0 ? GOLD : ((since / 3) % 3 == 1 ? BLUE : WHITE),
                        (int) Math.min(90, 30 + rr * 3), 12);
            }
            if (since < 16 && since % 2 == 0) {
                for (Vector top : holeTops) {
                    Fx.move(world, Particle.END_ROD, top, new Vector(0, 1, 0), 0.35 * Math.sqrt(k));
                    Fx.move(world, Particle.CLOUD, top, new Vector(0, 1, 0), 0.15 * Math.sqrt(k));
                }
            }
            digHoles(since);
            shockwave(rider, since);
        }

        private int sinkTicks = 8;
        private Vector sinkFrom;

        /** When the rider gets off: after it has fully sunk in and the impact has played out. */
        private int releaseTick() {
            return Math.max(sinkTicks + 16, LINGER - 14);
        }

        /** How far the trident drives in after touching down: the whole head plus a bit of shaft. */
        private double sinkDepth() {
            return prongLen + crossDip + 1.2 * g;
        }

        // blue / gold / white
        private final org.bukkit.Color GOLD = org.bukkit.Color.fromRGB(0xFFC83D);
        private final org.bukkit.Color GOLD_LIGHT = org.bukkit.Color.fromRGB(0xFFE08A);
        private final org.bukkit.Color BLUE = org.bukkit.Color.fromRGB(0x2F7BFF);
        private final org.bukkit.Color BLUE_LIGHT = org.bukkit.Color.fromRGB(0x7FD8FF);
        private final org.bukkit.Color WHITE = org.bukkit.Color.WHITE;

        private org.bukkit.Color randomColor() {
            double r = random.nextDouble();
            if (r < 0.25) {
                return GOLD;
            } else if (r < 0.4) {
                return GOLD_LIGHT;
            } else if (r < 0.6) {
                return BLUE;
            } else if (r < 0.78) {
                return BLUE_LIGHT;
            }
            return WHITE;
        }

        /** A coloured streak that flies from 'from' to 'to'. */
        private void streak(Vector from, Vector to, org.bukkit.Color color, int duration) {
            world.spawnParticle(Particle.TRAIL, from.getX(), from.getY(), from.getZ(), 1, 0, 0, 0, 0,
                    new Particle.Trail(to.toLocation(world), color, duration), true);
        }

        /** The main blue / gold / white explosion of streaks and sparkles. */
        private void colorBurst(Vector at, double amount) {
            double sk = Math.sqrt(k);
            int n = (int) Math.min(500, 260 * sk * amount);
            for (int i = 0; i < n; i++) {
                double yaw = random.nextDouble() * Math.PI * 2;
                double elev = Math.toRadians(3 + random.nextDouble() * 80);
                Vector d = new Vector(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev));
                double dist = Math.min(70, (3 + random.nextDouble() * 12) * sk);
                Vector from = at.clone().add(new Vector((random.nextDouble() - 0.5) * 0.6 * k, 0.3, (random.nextDouble() - 0.5) * 0.6 * k));
                streak(from, from.clone().add(d.multiply(dist)), randomColor(), 8 + random.nextInt(18));
            }
            // sparkles and embers on top
            int m = (int) Math.min(200, 90 * sk * amount);
            for (int i = 0; i < m; i++) {
                Vector d = new Vector(random.nextDouble() - 0.5, 0.3 + random.nextDouble(), random.nextDouble() - 0.5);
                double sp = (0.3 + random.nextDouble() * 0.7) * sk;
                double r = random.nextDouble();
                if (r < 0.35) {
                    Fx.move(world, Particle.FIREWORK, at, d, sp * 0.45);
                } else if (r < 0.6) {
                    Fx.move(world, Particle.ELECTRIC_SPARK, at, d, sp);
                } else if (r < 0.8) {
                    Fx.move(world, Particle.WAX_ON, at, d, sp);
                } else {
                    Fx.move(world, Particle.END_ROD, at, d, sp * 0.4);
                }
            }
            // a pillar of light shooting up
            for (int i = 0; i < 12; i++) {
                Vector from = at.clone().add(new Vector((random.nextDouble() - 0.5) * k, 0, (random.nextDouble() - 0.5) * k));
                streak(from, from.clone().add(new Vector(0, (12 + random.nextDouble() * 14) * sk, 0)),
                        i % 3 == 0 ? GOLD : (i % 3 == 1 ? WHITE : BLUE_LIGHT), 12 + random.nextInt(10));
            }
        }

        /** A ring of streaks racing outward along the ground. */
        private void ring(Vector center, double radius, org.bukkit.Color color, int points, int duration) {
            for (int i = 0; i < points; i++) {
                double a = i * Math.PI * 2 / points;
                Vector from = center.clone().add(new Vector(Math.cos(a) * radius * 0.3, 0, Math.sin(a) * radius * 0.3));
                Vector to = center.clone().add(new Vector(Math.cos(a) * radius, 0.2, Math.sin(a) * radius));
                streak(from, to, color, duration);
            }
        }

        /** Gold and blue light spiralling up the planted shaft. */
        private void spiralAroundShaft(int since) {
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            Vector ground = endPoint.clone();
            Vector[] f = frame();
            for (int i = 0; i < 6; i++) {
                double ang = since * 0.5 + i * Math.PI / 3;
                double r = shaftR * 3 + 0.5 * Math.sqrt(k);
                Vector from = ground.clone().add(f[0].clone().multiply(Math.cos(ang) * r)).add(f[1].clone().multiply(Math.sin(ang) * r));
                streak(from, butt.clone().add(new Vector(0, 1, 0)), i % 2 == 0 ? GOLD : BLUE_LIGHT, 18);
            }
        }

        /** The trident breaking up into streaks of light that float away upward. */
        private void riseFromTrident(double visibility) {
            int n = (int) Math.min(120, 40 * Math.sqrt(k) * visibility);
            for (int i = 0; i < n; i++) {
                double s = random.nextDouble() * totalLen;
                Vector p = tip.clone().subtract(axis.clone().multiply(s));
                if (p.getY() < endPoint.getY()) {
                    continue; // still underground
                }
                streak(p, p.clone().add(new Vector((random.nextDouble() - 0.5) * 2, (3 + random.nextDouble() * 5) * Math.sqrt(k),
                        (random.nextDouble() - 0.5) * 2)), randomColor(), 14 + random.nextInt(10));
            }
        }

        private void shockwave(Player rider, int since) {
            double r = since * waveSpeed;
            if (r > waveRadius) {
                return;
            }
            double spacing = 0.6 * Math.sqrt(k);
            int n = (int) (2 * Math.PI * r / spacing) + 8;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n;
                double x = endPoint.getX() + Math.cos(a) * r;
                double z = endPoint.getZ() + Math.sin(a) * r;
                Block top = Terrain.surface(world, x, z, endPoint.getY());
                if (top == null) {
                    continue;
                }
                Vector p = new Vector(x, top.getY() + 1.05, z);
                Vector outward = new Vector(Math.cos(a), 0.7 + random.nextDouble() * 0.5, Math.sin(a));
                Fx.spawn(world, Particle.BLOCK, p, 2, 0.15 * k, 0.05, 0.15 * k, 0.1, top.getBlockData());
                Fx.dust(world, p.clone().add(new Vector(0, 0.3, 0)), water);
                if (i % (k > 6 ? 3 : 1) == 0) {
                    Fx.move(world, Particle.ITEM, p, outward, (0.18 + random.nextDouble() * 0.2) * Math.sqrt(k), chip);
                }
                if (i % 3 == 0) {
                    streak(p, p.clone().add(outward.clone().multiply(2 + random.nextDouble() * 3)), randomColor(), 10);
                }
                if (i % 4 == 0) {
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 1, 0.1, 0.05);
                }
                if (i % 6 == 0) {
                    Fx.spawn(world, Particle.SWEEP_ATTACK, p.clone().add(new Vector(0, 0.4, 0)), 1, 0, 0);
                }
            }
            if (since % 3 == 0) {
                world.playSound(endPoint.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.4f,
                        0.45f + (float) (r / waveRadius) * 0.3f);
            }

            if (since % 2 == 1 && r < waveRadius - waveSpeed) {
                return; // check for creatures every other tick (the ring still catches everyone)
            }
            FileConfiguration cfg = plugin.getConfig();
            double maxDamage = cfg.getDouble("trident.max-damage", 14);
            double minDamage = cfg.getDouble("trident.min-damage", 5);
            double knockback = cfg.getDouble("trident.knockback", 1.8) * Math.sqrt(k);
            double yRange = 5 * Math.sqrt(k);
            for (Entity e : world.getNearbyEntities(endPoint.toLocation(world), waveRadius + 1, yRange + 1, waveRadius + 1)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || waveHit.contains(e.getUniqueId())
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                Vector pos = le.getLocation().toVector();
                double dist = Math.hypot(pos.getX() - endPoint.getX(), pos.getZ() - endPoint.getZ());
                if (dist > r || Math.abs(pos.getY() - endPoint.getY()) > yRange) {
                    continue;
                }
                waveHit.add(e.getUniqueId());
                WallManager.WallHit block = walls.blockingWall(le, endPoint.clone().add(new Vector(0, 1, 0)), riderId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }
                double damage = maxDamage - (maxDamage - minDamage) * (dist / Math.max(0.1, waveRadius));
                if (rider != null) {
                    le.damage(damage, rider);
                } else {
                    le.damage(damage);
                }
                Vector push = pos.clone().subtract(endPoint).setY(0);
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                }
                push.normalize().multiply(knockback * (1 - 0.5 * dist / Math.max(0.1, waveRadius)));
                push.setY(Math.min(1.5, 0.55 * Math.sqrt(k)));
                le.setVelocity(push);
            }
        }

        // ------------------------------------------------------------ clash with an Aegis Wall

        private void clash(Player rider, WallManager.WallHit hit) {
            endedAt = t;
            clashed = true;
            endPoint = hit.point().clone();
            Wall wall = hit.wall();
            FileConfiguration cfg = plugin.getConfig();

            // Rider is thrown back off the shattered trident
            dismount(rider, true);

            // Wall holder is shoved back but safe
            Player owner = Bukkit.getPlayer(wall.owner);
            if (owner != null) {
                owner.setVelocity(wall.normal.clone().multiply(-0.9).setY(0.35));
            }
            for (int i = 0; i < 4; i++) {
                walls.impact(wall, endPoint);
            }

            Location loc = endPoint.toLocation(world);
            world.playSound(loc, Sound.ITEM_TRIDENT_THUNDER, volume * 1.5f, 0.7f);
            world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, volume * 1.5f, 0.6f);
            world.playSound(loc, Sound.BLOCK_ANVIL_LAND, volume * 1.2f, 0.5f);
            world.playSound(loc, Sound.ITEM_SHIELD_BLOCK, volume * 1.5f, 0.5f);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, volume, 0.9f);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 1.5f, 0.7f);
            world.playSound(loc, Sound.ITEM_TRIDENT_HIT, volume, 0.5f);

            BlockData rock = Material.PRISMARINE.createBlockData();
            ItemStack shard = new ItemStack(Material.PRISMARINE_SHARD);
            groundData = rock;
            chip = shard;

            Fx.spawn(world, Particle.EXPLOSION_EMITTER, endPoint, (int) Math.ceil(k), 0.5 * k, 0);
            Fx.spawn(world, Particle.SONIC_BOOM, endPoint, (int) Math.ceil(2 * k), 0.8 * k, 0);
            Fx.spawn(world, Particle.FLASH, endPoint, 1, 0, 0, 0, 0, org.bukkit.Color.WHITE);
            burst(endPoint, 1.4, true);

            // Air shockwave shoves everything nearby
            double airRadius = 8 * Math.pow(k, 0.7);
            double clashDamage = cfg.getDouble("trident.clash-damage", 8);
            for (Entity e : world.getNearbyEntities(loc, airRadius, airRadius, airRadius)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(riderId) || e.getUniqueId().equals(wall.owner)
                        || le.isDead() || isImmune(le)) {
                    continue;
                }
                Vector away = le.getLocation().toVector().subtract(endPoint);
                double dist = away.length();
                if (dist > airRadius) {
                    continue;
                }
                if (away.lengthSquared() < 1e-4) {
                    away = new Vector(0, 1, 0);
                }
                if (clashDamage > 0) {
                    if (rider != null) {
                        le.damage(clashDamage, rider);
                    } else {
                        le.damage(clashDamage);
                    }
                }
                le.setVelocity(away.normalize().multiply(1.6 * (1 - 0.5 * dist / airRadius)).setY(0.6));
            }

            // Spikes shoot out of the collision
            int count = Math.max(0, cfg.getInt("trident.clash-spikes", 10));
            double spikeDamage = cfg.getDouble("axe.spike-damage", 6);
            if (count > 0) {
                scar = terrain.newScar();
            }
            for (int i = 0; i < count; i++) {
                double ang = i * Math.PI * 2 / count + random.nextDouble() * 0.4;
                Vector d = wall.right.clone().multiply(Math.cos(ang)).add(wall.up.clone().multiply(Math.sin(ang)))
                        .add(wall.normal.clone().multiply((random.nextDouble() - 0.5) * 0.9));
                d.setY(d.getY() + 0.35);
                d.normalize().multiply((0.9 + random.nextDouble() * 0.6) * Math.sqrt(k));
                new Spike(walls, random, world, endPoint.clone(), d, k, riderId, scar,
                        plugin.getConfig().getBoolean("axe.crater", true), shard, rock, spikeDamage, volume)
                        .runTaskTimer(plugin, 1L, 1L);
            }
        }

        private void clashAftermath(int since) {
            // The trident shatters apart
            if (since <= 18) {
                drawShattered(since);
            }
            // Rings of force blasting outward in the air
            if (since <= 14) {
                double r = since * 0.9 * Math.pow(k, 0.7);
                Vector[] f = frame();
                int n = (int) (2 * Math.PI * r / (0.5 * Math.sqrt(k))) + 10;
                for (int i = 0; i < n; i++) {
                    double a = i * 2 * Math.PI / n;
                    Vector ringA = endPoint.clone().add(f[0].clone().multiply(Math.cos(a) * r)).add(f[1].clone().multiply(Math.sin(a) * r));
                    Vector ringB = endPoint.clone().add(new Vector(Math.cos(a) * r * 0.8, 0, Math.sin(a) * r * 0.8));
                    Fx.dust(world, ringA, since < 5 ? prongEdge : water);
                    if (i % 2 == 0) {
                        Fx.spawn(world, Particle.CLOUD, ringB, 1, 0.1, 0.02);
                    }
                    if (i % 5 == 0) {
                        Fx.spawn(world, Particle.ELECTRIC_SPARK, ringA, 1, 0.05, 0.05);
                        Fx.spawn(world, Particle.SWEEP_ATTACK, ringB, 1, 0, 0);
                    }
                }
                if (since % 3 == 0) {
                    world.playSound(endPoint.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.5f, 0.9f);
                }
            }
            if (since >= 1 && since <= 6) {
                burst(endPoint, 0.5 / since, true);
            }
        }

        private void dismount(Player rider, boolean thrownBack) {
            riders.remove(riderId);
            if (vehicle != null && vehicle.isValid()) {
                vehicle.eject();
                vehicle.remove();
            }
            vehicle = null;
            if (rider == null) {
                return;
            }
            rider.setFallDistance(0);
            if (thrownBack) {
                Vector back = axis.clone().multiply(-1.3).setY(0.7);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    rider.setVelocity(back);
                    rider.setFallDistance(0);
                });
                rider.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, true));
            } else {
                // Stand right in front of the planted trident (just past the prongs), facing it
                double out = holeRadius() + 2.5;
                Vector spot = endPoint.clone().add(heading.clone().multiply(out));
                int y = topAt(spot.getX(), spot.getZ(), endPoint.getY()) + 1;
                for (int i = 0; i < 12 && (world.getBlockAt(spot.getBlockX(), y, spot.getBlockZ()).getType().isSolid()
                        || world.getBlockAt(spot.getBlockX(), y + 1, spot.getBlockZ()).getType().isSolid()); i++) {
                    y++;
                }
                for (int i = 0; i < 80 && world.getBlockAt(spot.getBlockX(), y, spot.getBlockZ()).isLiquid(); i++) {
                    y++; // landed in water: come up to the surface
                }
                Vector look = heading.clone().multiply(-1);
                float yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
                Location to = new Location(world, spot.getX(), y, spot.getZ(), yaw, -25f);
                rider.teleport(to);
                rider.setFallDistance(0);
                rider.setVelocity(new Vector(0, 0, 0));
                Fx.spawn(world, Particle.END_ROD, to.toVector().add(new Vector(0, 1, 0)), 20, 0.4, 0.08);
                ring(to.toVector().add(new Vector(0, 0.2, 0)), 1.6, GOLD, 18, 8);
            }
        }

        private void finish() {
            if (vehicle != null) {
                dismount(Bukkit.getPlayer(riderId), false);
            }
            if (scar != null) {
                scar.finish();
            }
            cancel();
            rides.remove(this);
        }

        void cleanup() {
            riders.remove(riderId);
            if (vehicle != null && vehicle.isValid()) {
                vehicle.eject();
                vehicle.remove();
            }
            vehicle = null;
            if (scar != null) {
                scar.finish();
                scar = null;
            }
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // not scheduled yet
            }
            rides.remove(this);
        }

        /** Real ground (not leaves, glass panes, fences...). */
        private boolean isGround(Block b) {
            Material m = b.getType();
            return m.isSolid() && !org.bukkit.Tag.LEAVES.isTagged(m) && m.isOccluding();
        }

        private boolean isImmune(LivingEntity le) {
            return le instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR);
        }

        // ------------------------------------------------------------ particles

        /** Two unit vectors perpendicular to the trident: [0] = sideways, [1] = "up" relative to it. */
        private Vector[] frame() {
            Vector side = axis.getCrossProduct(UP);
            if (side.lengthSquared() < 1e-4) {
                side = new Vector(1, 0, 0);
            }
            side.normalize();
            Vector up2 = side.getCrossProduct(axis).normalize();
            return new Vector[]{side, up2};
        }

        /** Huge spray of water, sparks, chips and glow flying out of a point. */
        private void burst(Vector at, double amount, boolean sphere) {
            int n = (int) (360 * Math.sqrt(k) * amount);
            double sk = Math.sqrt(k);
            for (int i = 0; i < n; i++) {
                double yaw = random.nextDouble() * Math.PI * 2;
                double elev = sphere ? Math.asin(random.nextDouble() * 2 - 1) : Math.toRadians(4 + random.nextDouble() * 62);
                Vector d = new Vector(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev));
                Vector from = at.clone().add(new Vector((random.nextDouble() - 0.5) * k, random.nextDouble() * 0.5 * k,
                        (random.nextDouble() - 0.5) * k));
                double speed = (0.3 + random.nextDouble() * 0.9) * sk;
                double r = random.nextDouble();
                if (r < 0.3 && chip != null) {
                    Fx.move(world, Particle.ITEM, from, d, speed * 0.9, chip);
                } else if (r < 0.5) {
                    Fx.move(world, Particle.SPLASH, from, d, speed * 1.2);
                } else if (r < 0.62) {
                    Fx.move(world, Particle.ELECTRIC_SPARK, from, d, speed);
                } else if (r < 0.72) {
                    Fx.move(world, Particle.GLOW, from, d, speed * 0.5);
                } else if (r < 0.82) {
                    Fx.move(world, Particle.FIREWORK, from, d, speed * 0.45);
                } else if (r < 0.9) {
                    Fx.move(world, Particle.CLOUD, from, d, speed * 0.3);
                } else if (r < 0.96) {
                    Fx.move(world, Particle.CRIT, from, d, speed);
                } else {
                    Fx.move(world, Particle.END_ROD, from, d, speed * 0.4);
                }
            }
        }

        private boolean hidden(double visibility) {
            return visibility < 1 && random.nextDouble() > visibility;
        }

        /**
         * The giant trident, drawn like the Minecraft one: long teal shaft with light bands and a pointed butt,
         * a curved crossbar, a long centre prong and two side prongs, each with a spear-point tip and barbs.
         * The prongs spread out sideways, so it lands three prongs in a row.
         */
        private void drawTrident(double visibility, double spin) {
            Vector[] f = frame();
            Vector side = f[0];
            Vector up2 = f[1];
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            int ring = k < 3 ? 1 : 3;

            // Shaft with light bands (grip, middle and neck)
            double band = 0.7 * g;
            for (double s = 0; s <= shaftLen; s += step) {
                boolean light = s < band
                        || Math.abs(s - shaftLen * 0.42) < band * 0.6
                        || (s > shaftLen - band * 2.2 && s < shaftLen - band);
                for (int i = 0; i < ring; i++) {
                    if (hidden(visibility)) {
                        continue;
                    }
                    double ang = spin + i * Math.PI * 2 / ring;
                    double rr = ring == 1 ? 0 : shaftR;
                    Vector p = at(butt, s, side, up2, Math.cos(ang) * rr, Math.sin(ang) * rr);
                    Fx.dust(world, p, light ? gold : (Math.cos(ang) > 0.2 ? shaftLight : shaftDark));
                }
            }
            // Pointed butt cap
            for (double s = 0; s <= 1.2 * g; s += step * 0.7) {
                if (!hidden(visibility)) {
                    Fx.dust(world, at(butt, -s, side, up2, 0, 0), s > 0.9 * g ? prongEdge : prong);
                }
            }

            // Curved crossbar (a shallow U), two rows thick
            double w = headHalfWidth;
            for (double x = -w; x <= w; x += step * 0.7) {
                double sx = shaftLen + crossDip * (x / w) * (x / w);
                for (int row = 0; row < 2; row++) {
                    if (hidden(visibility)) {
                        continue;
                    }
                    double back = row * prongWidth * 0.8;
                    Fx.dust(world, at(butt, sx - back, side, up2, x, 0), row == 0 ? prongEdge : prong);
                }
            }
            // Glowing gem where the shaft meets the head
            if (!hidden(visibility)) {
                Fx.fade(world, at(butt, shaftLen - prongWidth * 0.4, side, up2, 0, 0), gem);
            }

            // Prongs: long centre one, and a shorter one on each end of the crossbar
            drawProng(butt, side, up2, shaftLen, 0, prongLen, 0, visibility);
            drawProng(butt, side, up2, shaftLen + crossDip, -w, sideLen, -1, visibility);
            drawProng(butt, side, up2, shaftLen + crossDip, w, sideLen, 1, visibility);

            if (!hidden(visibility)) {
                Fx.spawn(world, Particle.END_ROD, tip, 1, 0, 0);
            }
        }

        /** One prong: straight part, then a spear-point head with barbs. sgn = -1 left, 0 centre, 1 right. */
        private void drawProng(Vector butt, Vector side, Vector up2, double s0, double x0, double len, int sgn,
                               double visibility) {
            double w = headHalfWidth;
            double pw = prongWidth;
            boolean thick = k >= 3;
            double barbAt = 0.7;
            double barbX = x0 - sgn * 0.12 * w * barbAt * barbAt;
            for (double t = 0; t <= len; t += step * 0.7) {
                double f = t / len;
                double x = x0 - sgn * 0.12 * w * f * f; // side prongs lean in a touch, like the real thing
                double width;
                if (f < 0.62) {
                    width = pw;
                } else if (f < 0.72) {
                    width = pw * (1 + 0.9 * (f - 0.62) / 0.10);
                } else {
                    width = 1.9 * pw * (1 - (f - 0.72) / 0.28);
                }
                if (width < step * 0.7) {
                    if (!hidden(visibility)) {
                        Vector p = at(butt, s0 + t, side, up2, x, 0);
                        if (f > 0.9) {
                            Fx.fade(world, p, tipGlow);
                        } else {
                            Fx.dust(world, p, prongEdge);
                        }
                    }
                    continue;
                }
                double dxStep = k >= 4 ? Math.max(step * 0.7, width / 2) : step * 0.7; // outline only when huge
                for (double dx = -width / 2; dx <= width / 2 + 1e-9; dx += dxStep) {
                    if (hidden(visibility)) {
                        continue;
                    }
                    Vector p = at(butt, s0 + t, side, up2, x + dx, 0);
                    if (f > 0.9) {
                        Fx.fade(world, p, tipGlow);
                    } else {
                        Fx.dust(world, p, Math.abs(dx) > width / 2 - step * 0.7 ? prongEdge : prong);
                    }
                    if (thick && Math.abs(dx) < step) {
                        Fx.dust(world, at(butt, s0 + t, side, up2, x + dx, pw * 0.3), prong);
                        Fx.dust(world, at(butt, s0 + t, side, up2, x + dx, -pw * 0.3), prong);
                    }
                }
            }
            // Barbs hooking backwards off the spear point
            double barbLen = 0.9 * g;
            double sBarb = s0 + len * barbAt;
            int[] dirs = sgn == 0 ? new int[]{-1, 1} : new int[]{sgn};
            for (int d : dirs) {
                for (double u = 0; u <= barbLen; u += step * 0.7) {
                    if (hidden(visibility)) {
                        continue;
                    }
                    Vector p = at(butt, sBarb - u * 0.8, side, up2, barbX + d * (pw * 0.95 + u * 0.55), 0);
                    Fx.dust(world, p, prongEdge);
                }
            }
        }

        /** The trident blowing apart after hitting a wall. */
        private void drawShattered(int since) {
            double spreadOut = since * 0.35 * Math.sqrt(k);
            double visibility = 1.0 - since / 18.0;
            Vector butt = tip.clone().subtract(axis.clone().multiply(totalLen));
            for (double s = 0; s <= totalLen; s += step * 1.5) {
                if (random.nextDouble() > visibility) {
                    continue;
                }
                Vector p = butt.clone().add(axis.clone().multiply(s));
                Vector away = p.clone().subtract(endPoint);
                if (away.lengthSquared() < 1e-4) {
                    away = new Vector(0, 1, 0);
                }
                away.normalize().multiply(spreadOut * (0.5 + random.nextDouble()));
                away.setY(away.getY() - 0.02 * since * since);
                p.add(away);
                Fx.dust(world, p, s > shaftLen ? prong : (random.nextBoolean() ? shaftDark : gold));
                if (random.nextInt(6) == 0) {
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, p, 1, 0.1, 0.05);
                }
            }
        }

        private Vector at(Vector butt, double s, Vector side, Vector up2, double x, double y) {
            return new Vector(
                    butt.getX() + axis.getX() * s + side.getX() * x + up2.getX() * y,
                    butt.getY() + axis.getY() * s + side.getY() * x + up2.getY() * y,
                    butt.getZ() + axis.getZ() * s + side.getZ() * x + up2.getZ() * y);
        }
    }
}
