package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The Titan Cleaver's sneak + right click "Titan Slam". */
final class TitanAxe implements Listener {

    private static final double WIND_UP = Math.toRadians(-38);
    private static final double START = Math.toRadians(-8);
    private static final double DOWN = Math.toRadians(90);

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;
    private final Terrain terrain;
    private final NamespacedKey scaleKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    TitanAxe(AegisTitan plugin, Items items, WallManager walls, Terrain terrain) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
        this.terrain = terrain;
        this.scaleKey = new NamespacedKey(plugin, "axe_scale");
    }

    // ------------------------------------------------------------------ axe size

    double maxScale() {
        return Math.max(1.0, plugin.getConfig().getDouble("axe.max-scale", 10.0));
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

    // ------------------------------------------------------------------ cooldown

    int cooldownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("axe.cooldown-seconds", 15));
    }

    /** Change the slam cooldown for everyone, save it, and shorten any cooldown already running. */
    void setCooldownSeconds(int seconds) {
        int s = Math.max(0, seconds);
        plugin.getConfig().set("axe.cooldown-seconds", s);
        plugin.saveConfig();
        long now = System.currentTimeMillis();
        long latest = now + s * 1000L;
        for (Map.Entry<UUID, Long> entry : cooldowns.entrySet()) {
            if (entry.getValue() > latest) {
                entry.setValue(latest);
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null) {
                    p.setCooldown(Material.NETHERITE_AXE, (int) ((latest - now) / 50));
                }
            }
        }
    }

    // ------------------------------------------------------------------ trigger

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !items.isAxe(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true); // no log stripping etc.

        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            double left = (ready - now) / 1000.0;
            player.sendActionBar(Component.text(String.format("Titan Slam recharging\u2026 %.1fs", left), NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.5f);
            return;
        }
        long cooldownMs = cooldownSeconds() * 1000L;
        cooldowns.put(player.getUniqueId(), now + cooldownMs);
        if (cooldownMs > 0) {
            player.setCooldown(Material.NETHERITE_AXE, (int) (cooldownMs / 50));
        }
        new Slam(player, getScale(player)).runTaskTimer(plugin, 0L, 1L);
    }

    // ================================================================== the slam

    private final class Slam extends BukkitRunnable {

        // --- size of everything (k = the axe size from /axesize)
        private final double k;
        private final double handle;
        private final double blade;
        private final double headPos;
        private final double axisHeight;
        private final double step;
        private final int summonTicks;
        private final int swingTicks;
        private final int impactTick;
        private final int lingerTicks;
        private final float volume;
        private final float pitch;

        private final Particle.DustOptions handleDark;
        private final Particle.DustOptions handleWrap;
        private final Particle.DustOptions bladeBody;
        private final Particle.DustOptions bladeTrim;
        private final Particle.DustOptions bladeEdge;
        private final Particle.DustTransition edgeFire;
        private final Particle.DustOptions shock;
        private final Particle.DustOptions shockHot;

        // --- where
        private final UUID casterId;
        private final World world;
        private final Vector impact;
        private final Vector forward;
        private final Vector side;
        private final Vector up = new Vector(0, 1, 0);
        private final Vector pivot;
        private final BlockData groundData;
        private final ItemStack chip;

        // --- the big cut through the ground
        private final double cutHalfLength;
        private final double cutHalfWidth;
        private final ArrayDeque<double[]> columns = new ArrayDeque<>(); // {x, z, |a|}
        private int cutFloorBase;
        private int cutFloorDepth;
        private int maxUp;
        private int blocksPerTick;
        private final boolean slice;
        private Terrain.Scar scar;

        // --- shockwave
        private final double radius;
        private final double waveSpeed;
        private final double maxDamage;
        private final double minDamage;
        private final double knockback;
        private final double cutRadius;
        private final boolean crater;
        private final boolean flyingDebris;
        private final Set<UUID> hit = new HashSet<>();

        private int t;
        private double lastTheta = START;

        Slam(Player player, double scale) {
            FileConfiguration cfg = plugin.getConfig();
            this.k = scale;
            this.handle = 11.0 * k;
            this.blade = 3.4 * k;
            this.headPos = handle - k;
            double biteDepth = Math.max(2.0, 1.5 * k);
            this.axisHeight = blade - biteDepth;
            this.step = 0.3 * Math.pow(k, 0.55);
            this.summonTicks = 14 + (int) Math.round(2 * (k - 1));
            this.swingTicks = 7 + (int) Math.round(0.9 * (k - 1));
            this.impactTick = summonTicks + swingTicks;
            this.lingerTicks = 26 + (int) Math.round(2 * (k - 1));
            this.volume = (float) (2.0 * Math.pow(k, 0.7));
            this.pitch = (float) (1.0 / Math.pow(k, 0.15));

            float big = (float) Math.min(4.0, 2.0 * Math.pow(k, 0.4));
            this.handleDark = Fx.dust(0x2B2527, big);
            this.handleWrap = Fx.dust(0x5A4636, big);
            this.bladeBody = Fx.dust(0x4D494F, Math.min(4f, big * 1.1f));
            this.bladeTrim = Fx.dust(0x6B3F7A, big);
            this.bladeEdge = Fx.dust(0x9C97A0, big * 0.9f);
            this.edgeFire = Fx.fade(0xFF7A1A, 0xFFE9A0, big * 0.85f);
            this.shock = Fx.dust(0xB9AE9C, Math.min(4f, big * 1.1f));
            this.shockHot = Fx.dust(0xFF8A2A, big * 0.8f);

            this.radius = cfg.getDouble("axe.shockwave-radius", 10) * Math.pow(k, 0.85);
            this.waveSpeed = radius / (12.0 + 2.0 * k);
            this.maxDamage = cfg.getDouble("axe.max-damage", 12);
            this.minDamage = cfg.getDouble("axe.min-damage", 4);
            this.knockback = cfg.getDouble("axe.knockback", 1.6) * Math.sqrt(k);
            this.cutRadius = cfg.getDouble("axe.shield-cut-radius", 7) * Math.pow(k, 0.85);
            this.crater = cfg.getBoolean("axe.crater", true);
            this.slice = cfg.getBoolean("axe.slice-terrain", true);
            this.flyingDebris = cfg.getBoolean("axe.flying-debris", true);
            this.blocksPerTick = Math.max(200, cfg.getInt("axe.blocks-per-tick", 6000));
            double range = cfg.getDouble("axe.range", 30) + 8 * (k - 1);

            this.cutHalfLength = 3.4 * k + 0.25 * k * k;
            this.cutHalfWidth = 0.5 + 0.25 * k;

            // ---------- aiming
            this.casterId = player.getUniqueId();
            this.world = player.getWorld();
            Location eye = player.getEyeLocation();
            Vector origin = eye.toVector();
            Vector dir = eye.getDirection().normalize();

            Vector target = null;
            double hitDist = range;
            RayTraceResult rt = world.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.8,
                    e -> e instanceof LivingEntity && !e.getUniqueId().equals(casterId));
            if (rt != null) {
                hitDist = rt.getHitPosition().distance(origin);
                if (rt.getHitEntity() != null) {
                    target = rt.getHitEntity().getLocation().toVector();
                } else {
                    // step back out of the block face so cliffs/mountain sides work
                    target = rt.getHitPosition().clone().subtract(dir.clone().multiply(0.3));
                }
            }
            WallManager.WallHit wallHit = walls.rayCast(world, origin, dir, hitDist, casterId);
            if (wallHit != null) {
                target = wallHit.point();
            }

            Vector yawDir = new Vector(-Math.sin(Math.toRadians(eye.getYaw())), 0, Math.cos(Math.toRadians(eye.getYaw())));
            if (target == null) {
                Vector flat = dir.clone().setY(0);
                if (flat.lengthSquared() < 1e-4) {
                    flat = yawDir.clone();
                }
                target = origin.clone().add(flat.normalize().multiply(12 * Math.sqrt(k)));
            }

            Vector fwd = target.clone().subtract(origin).setY(0);
            if (fwd.lengthSquared() < 0.01) {
                fwd = yawDir.clone();
            }
            fwd.normalize();
            double flatDist = Math.hypot(target.getX() - origin.getX(), target.getZ() - origin.getZ());
            if (flatDist < 3.5) {
                double y = target.getY();
                target = origin.clone().add(fwd.clone().multiply(3.5));
                target.setY(y);
            }

            this.impact = groundBelow(target);
            this.forward = fwd;
            this.side = fwd.getCrossProduct(up).normalize();
            this.pivot = impact.clone().subtract(forward.clone().multiply(headPos)).add(up.clone().multiply(axisHeight));

            Block ground = world.getBlockAt(impact.getBlockX(), impact.getBlockY() - 1, impact.getBlockZ());
            this.groundData = ground.getType().isSolid() ? ground.getBlockData() : Material.DIRT.createBlockData();
            Material chipType = groundData.getMaterial();
            this.chip = new ItemStack(chipType.isItem() && !chipType.isAir() ? chipType : Material.COBBLESTONE);
        }

        private Vector groundBelow(Vector target) {
            int x = (int) Math.floor(target.getX());
            int z = (int) Math.floor(target.getZ());
            int y = (int) Math.floor(target.getY() + 0.5);
            for (int i = 0; i < 6 && world.getBlockAt(x, y, z).getType().isSolid(); i++) {
                y++;
            }
            for (int i = 0; i < 64 && y > world.getMinHeight(); i++) {
                if (world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    break;
                }
                y--;
            }
            return new Vector(target.getX(), y, target.getZ());
        }

        @Override
        public void run() {
            Player caster = Bukkit.getPlayer(casterId);
            if (t == 0) {
                Location loc = pivot.toLocation(world);
                world.playSound(loc, Sound.ITEM_ARMOR_EQUIP_NETHERITE, volume, 0.5f * pitch);
                world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, volume, 0.5f * pitch);
                if (k >= 5) {
                    world.playSound(loc, Sound.ENTITY_WITHER_SPAWN, volume, 0.5f);
                }
            } else if (t == summonTicks / 2) {
                world.playSound(pivot.toLocation(world), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, volume, 0.6f * pitch);
            } else if (t == summonTicks) {
                Location loc = pivot.toLocation(world);
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, volume, 0.5f * pitch);
                world.playSound(loc, Sound.ENTITY_BREEZE_WIND_BURST, volume, 0.6f * pitch);
            }

            if (t < summonTicks) {
                summonFrame();
            } else if (t < impactTick) {
                swingFrame();
            } else if (t == impactTick) {
                impactFrame();
            }

            boolean done = false;
            if (t >= impactTick) {
                int since = t - impactTick;
                if (since <= lingerTicks && (k <= 3 || since % 2 == 0)) {
                    drawAxe(DOWN, 1.0 - since / (double) lingerTicks);
                }
                if (since <= lingerTicks) {
                    dissolve(since);
                }
                if (since >= 1 && since <= 6) {
                    burst(0.35 / since);
                }
                shockwave(caster, since);
                processCut(since);
                done = since > lingerTicks && since * waveSpeed > radius + 1 && columns.isEmpty();
            }
            if (done) {
                if (scar != null) {
                    scar.finish();
                }
                cancel();
                return;
            }
            t++;
        }

        // ------------------------------------------------------------ phases

        private void summonFrame() {
            double p = t / (double) summonTicks;
            double ease = 1 - Math.pow(1 - p, 3);
            double theta = START + (WIND_UP - START) * ease;
            lastTheta = theta;
            drawAxe(theta, Math.min(1, 0.15 + p * 1.1));

            Vector head = pivot.clone().add(axis(theta).multiply(headPos));
            for (int i = 0; i < 6; i++) {
                double a = t * 0.6 + i * Math.PI / 3;
                double r = (3.5 * (1 - p) + 0.6) * k;
                Vector from = head.clone().add(side.clone().multiply(Math.cos(a) * r)).add(up.clone().multiply(Math.sin(a) * r));
                Fx.move(world, Particle.SOUL_FIRE_FLAME, from, head.clone().subtract(from), 0.12);
            }
            Fx.spawn(world, Particle.REVERSE_PORTAL, head, (int) (10 * Math.sqrt(k)), 1.2 * k, 0.02);
            if (t % 3 == 0) {
                Fx.spawn(world, Particle.ENCHANT, head, 20, 1.5 * k, 1.0);
            }
        }

        private void swingFrame() {
            double p = (t - summonTicks + 1) / (double) swingTicks;
            double theta = WIND_UP + (DOWN - WIND_UP) * p * p * p;
            int ghosts = k > 3 ? 1 : 2;
            for (int g = 1; g <= ghosts; g++) {
                double mid = lastTheta + (theta - lastTheta) * g / (ghosts + 1.0);
                drawAxe(mid, 0.3);
            }
            drawAxe(theta, 1.0);
            Vector head = pivot.clone().add(axis(theta).multiply(headPos)).add(bladeDir(theta).multiply(blade * 0.6));
            Fx.spawn(world, Particle.SWEEP_ATTACK, head, 3, k, 0);
            Fx.spawn(world, Particle.CLOUD, head, 6, 0.8 * k, 0.05);
            lastTheta = theta;
        }

        private void impactFrame() {
            Location loc = impact.toLocation(world);
            world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, volume * 1.5f, 0.6f * pitch);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, volume * 1.5f, 0.55f * pitch);
            world.playSound(loc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, volume * 1.5f, 0.5f);
            world.playSound(loc, Sound.BLOCK_ANVIL_LAND, volume, 0.5f);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, volume, 0.7f * pitch);
            if (k >= 4) {
                world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, volume * 2, 0.5f);
            }

            Vector top = impact.clone().add(new Vector(0, 0.3, 0));
            Fx.spawn(world, Particle.EXPLOSION_EMITTER, top, (int) Math.ceil(k), 0.8 * k, 0);
            Fx.spawn(world, Particle.EXPLOSION, top, (int) (8 * k), 1.5 * k, 0);
            Fx.spawn(world, Particle.FLASH, top, 1, 0, 0, 0, 0, Color.WHITE);
            Fx.spawn(world, Particle.SONIC_BOOM, top, (int) Math.ceil(k), 0.5 * k, 0);
            Fx.spawn(world, Particle.BLOCK, top, (int) (160 * Math.sqrt(k)), 1.5 * k, 0.4 * k, 1.5 * k, 0.4, groundData);
            Fx.spawn(world, Particle.CAMPFIRE_COSY_SMOKE, top, (int) (20 * k), 1.2 * k, 0.3 * k, 1.2 * k, 0.03, null);
            Fx.spawn(world, Particle.LAVA, top, (int) (14 * Math.sqrt(k)), k, 0);
            Fx.spawn(world, Particle.DUST_PLUME, top, (int) (40 * k), 1.5 * k, 0.05);

            if (crater || slice) {
                scar = terrain.newScar();
            }
            burst(1.0);
            launchSpikes(caster());
            if (crater) {
                crackGround();
            }
            if (slice) {
                planCut();
            }

            // Shield walls caught by the blast or standing in the path of the cut get split in two
            for (Wall wall : walls.activeWalls(world, casterId)) {
                Vector c = wall.center;
                double a = c.clone().subtract(impact).dot(forward);
                double b = c.clone().subtract(impact).dot(side);
                boolean inCut = Math.abs(a) <= cutHalfLength && Math.abs(b) <= cutHalfWidth + wall.shape.w / 2 + 1;
                boolean nearBlast = wall.nearestPoint(impact).distance(impact) <= cutRadius;
                Player owner = Bukkit.getPlayer(wall.owner);
                boolean ownerNear = owner != null && owner.getLocation().toVector().distance(impact) <= cutRadius;
                if (inCut || nearBlast || ownerNear) {
                    walls.shatter(wall, impact);
                }
            }
        }

        private Player caster() {
            return Bukkit.getPlayer(casterId);
        }

        /** A huge spray of chips, sparks, embers and smoke flying out of the impact. */
        private void burst(double amount) {
            int n = (int) (320 * Math.sqrt(k) * amount);
            double sk = Math.sqrt(k);
            for (int i = 0; i < n; i++) {
                double yaw = random.nextDouble() * Math.PI * 2;
                double elev = Math.toRadians(4 + random.nextDouble() * 62);
                Vector d = new Vector(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev));
                Vector from = impact.clone().add(new Vector(
                        (random.nextDouble() - 0.5) * 1.2 * k, 0.3 + random.nextDouble() * 0.5 * k,
                        (random.nextDouble() - 0.5) * 1.2 * k));
                double speed = (0.3 + random.nextDouble() * 0.9) * sk;
                double r = random.nextDouble();
                if (r < 0.42) {
                    Fx.move(world, Particle.ITEM, from, d, speed * 0.9, chip);
                } else if (r < 0.57) {
                    Fx.move(world, Particle.FLAME, from, d, speed * 0.6);
                } else if (r < 0.63) {
                    Fx.move(world, Particle.SOUL_FIRE_FLAME, from, d, speed * 0.6);
                } else if (r < 0.72) {
                    Fx.move(world, Particle.CRIT, from, d, speed);
                } else if (r < 0.82) {
                    Fx.move(world, Particle.FIREWORK, from, d, speed * 0.45);
                } else if (r < 0.92) {
                    Fx.move(world, Particle.LARGE_SMOKE, from, d, speed * 0.25);
                } else if (r < 0.97) {
                    Fx.move(world, Particle.ELECTRIC_SPARK, from, d, speed);
                } else {
                    Fx.move(world, Particle.END_ROD, from, d, speed * 0.4);
                }
            }
        }

        /** Big glowing rock spikes shot out of the impact in several directions. */
        private void launchSpikes(Player caster) {
            int count = Math.max(0, plugin.getConfig().getInt("axe.spikes", 6));
            if (count == 0) {
                return;
            }
            double spikeDamage = plugin.getConfig().getDouble("axe.spike-damage", 6);
            double offset = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                double yaw = offset + i * Math.PI * 2 / count + (random.nextDouble() - 0.5) * 0.35;
                double elev = Math.toRadians(28 + random.nextDouble() * 20);
                double range = radius * (0.6 + random.nextDouble() * 0.5);
                double v = Math.sqrt(range * Spike.GRAVITY / Math.sin(2 * elev));
                Vector vel = new Vector(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev)).multiply(v);
                Vector start = impact.clone().add(new Vector(0, 0.6, 0)).add(vel.clone().normalize().multiply(0.4 * k));
                new Spike(walls, random, world, start, vel, k, casterId, scar, crater, chip, groundData, spikeDamage, volume)
                        .runTaskTimer(plugin, 1L, 1L);
            }
            world.playSound(impact.toLocation(world), Sound.ITEM_TRIDENT_THROW, volume, 0.5f * pitch);
            world.playSound(impact.toLocation(world), Sound.BLOCK_POINTED_DRIPSTONE_LAND, volume, 0.5f);
        }

        /** Broken, cracked ground around the impact and along both sides of the cut. */
        private void crackGround() {
            int count = (int) Math.min(900, 60 + 30 * Math.pow(k, 1.3));
            double rMin = 1.2 * Math.pow(k, 0.8);
            double rMax = 4.5 * Math.pow(k, 0.8);
            for (int i = 0; i < count; i++) {
                double x;
                double z;
                boolean nearCut;
                if (i % 2 == 0) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double r = rMin + random.nextDouble() * (rMax - rMin);
                    double a = Math.cos(ang) * r * 1.35;
                    double b = Math.sin(ang) * r * 0.9;
                    x = impact.getX() + forward.getX() * a + side.getX() * b;
                    z = impact.getZ() + forward.getZ() * a + side.getZ() * b;
                    nearCut = r < rMin + (rMax - rMin) * 0.4;
                } else {
                    double a = (random.nextDouble() * 2 - 1) * cutHalfLength;
                    double b = (random.nextBoolean() ? 1 : -1)
                            * (cutHalfWidth + 0.6 + random.nextDouble() * 1.5 * Math.pow(k, 0.6));
                    x = impact.getX() + forward.getX() * a + side.getX() * b;
                    z = impact.getZ() + forward.getZ() * a + side.getZ() * b;
                    nearCut = Math.abs(b) < cutHalfWidth + 1.5;
                }
                Block top = topBlock(x, z);
                if (top == null) {
                    continue;
                }
                if (scar.crack(top, random) && nearCut && random.nextDouble() < 0.35) {
                    scar.raise(top, random);
                }
            }
        }

        /** Work out the columns the blade splits, nearest to the impact first. */
        private void planCut() {
            double depth = Math.max(2, 1.2 * k);
            double maxDepth = 3 * k + 0.9 * k * k;
            maxUp = (int) Math.ceil(16 * k + 8);

            // The cut goes down to the lowest ground along its line (so mountains split all the way)
            int impactY = impact.getBlockY();
            int base = impactY - 1;
            for (double a = -cutHalfLength * 0.85; a <= cutHalfLength * 0.85; a += 1.0) {
                double x = impact.getX() + forward.getX() * a;
                double z = impact.getZ() + forward.getZ() * a;
                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                base = Math.min(base, world.getHighestBlockYAt(bx, bz, HeightMap.OCEAN_FLOOR));
            }
            base = Math.max(base, (int) Math.floor(impactY - 1 - maxDepth));
            cutFloorBase = base;
            cutFloorDepth = (int) Math.round(depth);

            Set<Long> seen = new HashSet<>();
            List<double[]> list = new ArrayList<>();
            for (double a = -cutHalfLength; a <= cutHalfLength; a += 0.5) {
                for (double b = -cutHalfWidth; b <= cutHalfWidth + 1e-9; b += 0.5) {
                    double x = impact.getX() + forward.getX() * a + side.getX() * b;
                    double z = impact.getZ() + forward.getZ() * a + side.getZ() * b;
                    int bx = (int) Math.floor(x);
                    int bz = (int) Math.floor(z);
                    long key = ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
                    if (seen.add(key)) {
                        list.add(new double[]{bx, bz, Math.abs(a)});
                    }
                }
            }
            list.sort(Comparator.comparingDouble(c -> c[2]));
            columns.addAll(list);
        }

        /** Split the ground a few thousand blocks per tick, racing outward from the impact. */
        private void processCut(int since) {
            if (columns.isEmpty() || scar == null) {
                return;
            }
            int budget = blocksPerTick;
            int impactY = impact.getBlockY();
            int n = 0;
            double frontier = 0;
            while (!columns.isEmpty() && budget > 0) {
                double[] col = columns.poll();
                int x = (int) col[0];
                int z = (int) col[1];
                frontier = col[2];
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                int top = Math.min(world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING), impactY + maxUp);
                double f = Math.pow(Math.min(1.0, col[2] / cutHalfLength), 6);
                int floor = cutFloorBase - cutFloorDepth;
                int bottom = (int) Math.round(floor + (top - floor) * f);
                bottom = Math.max(bottom, world.getMinHeight() + 1);
                BlockData topData = null;
                for (int y = top; y >= bottom; y--) {
                    budget--;
                    Block b = world.getBlockAt(x, y, z);
                    if (topData == null && b.getType().isSolid()) {
                        topData = b.getBlockData();
                    }
                    scar.cut(b);
                }
                if (topData != null && n++ % 4 == 0) {
                    Vector p = new Vector(x + 0.5, top + 0.6, z + 0.5);
                    Fx.spawn(world, Particle.BLOCK, p, 6, 0.4, 0.4, 0.4, 0.2, topData);
                    if (random.nextInt(3) == 0) {
                        Fx.spawn(world, Particle.CAMPFIRE_COSY_SMOKE, p, 1, 0.3, 0.2, 0.3, 0.02, null);
                    }
                    if (flyingDebris && topData.getMaterial().isOccluding() && random.nextInt(10) == 0) {
                        double s = random.nextBoolean() ? 1 : -1;
                        Vector vel = side.clone().multiply(s * 0.25).setY(0.45 + random.nextDouble() * 0.25);
                        terrain.launchDebris(world, p.clone().add(new Vector(0, 0.6, 0)), topData, vel);
                    }
                }
            }
            if (since % 4 == 0) {
                for (int s = -1; s <= 1; s += 2) {
                    Vector front = impact.clone().add(forward.clone().multiply(s * frontier));
                    Block top = topBlock(front.getX(), front.getZ());
                    if (top != null) {
                        Vector p = top.getLocation().toVector().add(new Vector(0.5, 1, 0.5));
                        Fx.spawn(world, Particle.EXPLOSION, p, 2, 0.6, 0);
                        world.playSound(p.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.6f, 0.5f);
                        world.playSound(p.toLocation(world), Sound.BLOCK_DEEPSLATE_BREAK, volume, 0.5f);
                    }
                }
            }
        }

        private Block topBlock(double x, double z) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                return null;
            }
            int y = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y <= world.getMinHeight()) {
                return null;
            }
            Block b = world.getBlockAt(bx, y, bz);
            return b.getType().isSolid() ? b : null;
        }

        private void dissolve(int since) {
            if (since < 12) {
                Fx.spawn(world, Particle.LAVA, impact, (int) Math.ceil(2 * Math.sqrt(k)), 0.8 * k, 0);
                Fx.spawn(world, Particle.CAMPFIRE_COSY_SMOKE, impact, (int) Math.ceil(2 * k), 1.5 * k, 0.2, 1.5 * k, 0.02, null);
            }
            for (int i = 0; i < 8 * Math.sqrt(k); i++) {
                double s = random.nextDouble() * (handle + 0.5 * k);
                Vector p = pivot.clone().add(axis(DOWN).multiply(s));
                Fx.move(world, Particle.WHITE_ASH, p, new Vector(0, 1, 0), 0.05);
                if (random.nextInt(3) == 0) {
                    Fx.move(world, Particle.SOUL, p, new Vector(0, 1, 0), 0.04);
                }
            }
        }

        private void shockwave(Player caster, int since) {
            double r = since * waveSpeed;
            if (r > radius) {
                return;
            }
            double spacing = 0.6 * Math.sqrt(k);
            int n = (int) (2 * Math.PI * r / spacing) + 8;
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n;
                double x = impact.getX() + Math.cos(a) * r;
                double z = impact.getZ() + Math.sin(a) * r;
                Block top = k <= 2 ? Terrain.surface(world, x, z, impact.getY()) : topBlock(x, z);
                if (top == null) {
                    continue;
                }
                Vector p = new Vector(x, top.getY() + 1.05, z);
                BlockData data = top.getBlockData();
                Fx.spawn(world, Particle.BLOCK, p, 2, 0.15 * k, 0.05, 0.15 * k, 0.1, data);
                Fx.dust(world, p.clone().add(new Vector(0, 0.25, 0)), since < 4 ? shockHot : shock);
                if (i % 3 == 0) {
                    Fx.spawn(world, Particle.POOF, p, 1, 0.1, 0.02);
                }
                // chips and embers kicked outward by the wave
                Vector outward = new Vector(Math.cos(a), 0.6 + random.nextDouble() * 0.6, Math.sin(a));
                Fx.move(world, Particle.ITEM, p, outward, (0.18 + random.nextDouble() * 0.2) * Math.sqrt(k), chip);
                if (i % 2 == 0) {
                    Fx.move(world, since < 5 ? Particle.FLAME : Particle.LARGE_SMOKE, p, outward,
                            (0.08 + random.nextDouble() * 0.1) * Math.sqrt(k));
                }
                if (i % 6 == 0) {
                    Fx.spawn(world, Particle.SWEEP_ATTACK, p.clone().add(new Vector(0, 0.4, 0)), 1, 0, 0);
                }
                if (crater && scar != null && k >= 2 && random.nextInt(12) == 0) {
                    scar.crack(top, random); // the wave breaks the ground as it passes
                }
                if (flyingDebris && since % 2 == 0 && random.nextInt(7) == 0 && top.getType().isOccluding()) {
                    Vector out = new Vector(Math.cos(a), 0, Math.sin(a)).multiply(0.12 * Math.sqrt(k));
                    out.setY(0.32 + random.nextDouble() * 0.18 * Math.sqrt(k));
                    terrain.launchDebris(world, new Vector(Math.floor(x) + 0.5, top.getY() + 1.0, Math.floor(z) + 0.5), data, out);
                }
            }
            if (since % 3 == 0) {
                world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, volume * 0.4f,
                        0.4f + (float) (r / radius) * 0.3f);
            }

            double yRange = 5 * Math.sqrt(k);
            Location center = impact.toLocation(world);
            for (Entity e : world.getNearbyEntities(center, radius + 1, yRange + 1, radius + 1)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(casterId) || hit.contains(e.getUniqueId())) {
                    continue;
                }
                if (le.isDead() || (le instanceof Player pl
                        && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR))) {
                    continue;
                }
                Vector pos = le.getLocation().toVector();
                double dist = Math.hypot(pos.getX() - impact.getX(), pos.getZ() - impact.getZ());
                if (dist > r || dist > radius || Math.abs(pos.getY() - impact.getY()) > yRange) {
                    continue;
                }
                hit.add(e.getUniqueId());

                WallManager.WallHit block = walls.blockingWall(le, impact.clone().add(new Vector(0, 1, 0)), casterId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }

                double damage = maxDamage - (maxDamage - minDamage) * (dist / Math.max(0.1, radius));
                if (caster != null) {
                    le.damage(damage, caster);
                } else {
                    le.damage(damage);
                }
                Vector push = pos.clone().subtract(impact).setY(0);
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                }
                push.normalize().multiply(knockback * (1 - 0.5 * dist / Math.max(0.1, radius)));
                push.setY(Math.min(1.5, 0.55 * Math.sqrt(k)));
                le.setVelocity(push);
                Fx.spawn(world, Particle.CRIT, pos.clone().add(new Vector(0, 1, 0)), 12, 0.3, 0.3);
            }
        }

        // ------------------------------------------------------------ the particle axe

        private Vector axis(double theta) {
            return forward.clone().multiply(Math.sin(theta)).add(up.clone().multiply(Math.cos(theta)));
        }

        private Vector bladeDir(double theta) {
            return forward.clone().multiply(Math.cos(theta)).subtract(up.clone().multiply(Math.sin(theta)));
        }

        private void drawAxe(double theta, double visibility) {
            Vector a = axis(theta);
            Vector b = bladeDir(theta);
            double edgeHalf = 2.3 * k;

            // Handle
            for (double s = -0.4 * k; s <= handle + 0.5 * k; s += step) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                boolean wrap = s < handle * 0.55 && ((int) Math.floor(s / (1.1 * k))) % 2 == 0;
                Fx.dust(world, at(a, b, s, 0), wrap ? handleWrap : handleDark);
            }
            // Pommel
            if (visibility >= 1 || random.nextDouble() < visibility) {
                Fx.dust(world, at(a, b, -0.6 * k, 0.25 * k), bladeTrim);
                Fx.dust(world, at(a, b, -0.6 * k, -0.25 * k), bladeTrim);
            }

            // Main blade, flaring out to a curved cutting edge
            for (double tt = 0; tt <= blade; tt += step) {
                double hl = k * (0.75 + 1.55 * Math.pow(tt / blade, 1.6));
                for (double q = -hl; q <= hl; q += step) {
                    double edgeT = blade - 0.55 * k * (q / edgeHalf) * (q / edgeHalf);
                    if (tt > edgeT) {
                        continue;
                    }
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Particle.DustOptions color;
                    if (tt > edgeT - 1.5 * step) {
                        color = bladeEdge;
                    } else if (Math.abs(q) > hl - step || tt < step) {
                        color = bladeTrim;
                    } else {
                        color = bladeBody;
                    }
                    Fx.dust(world, at(a, b, headPos + q, tt), color);
                }
            }

            // Red-hot cutting edge (Fire Aspect)
            for (double q = -edgeHalf; q <= edgeHalf; q += step * 1.1) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                double tt = blade - 0.55 * k * (q / edgeHalf) * (q / edgeHalf);
                Vector p = at(a, b, headPos + q, tt);
                Fx.fade(world, p, edgeFire);
                if (random.nextInt(8) == 0) {
                    Fx.spawn(world, Particle.FLAME, p, (int) Math.ceil(k), 0.05 * k, 0.01);
                }
            }

            // Back spike
            for (double tt = 0.3 * k; tt <= 1.3 * k; tt += step) {
                double hl = 0.55 * k * (1 - (tt - 0.3 * k) / k);
                for (double q = -hl; q <= hl + 1e-9; q += step) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Fx.dust(world, at(a, b, headPos + q, -tt), bladeBody);
                }
            }
        }

        private Vector at(Vector axis, Vector bladeVec, double s, double tt) {
            return new Vector(
                    pivot.getX() + axis.getX() * s + bladeVec.getX() * tt,
                    pivot.getY() + axis.getY() * s + bladeVec.getY() * tt,
                    pivot.getZ() + axis.getZ() * s + bladeVec.getZ() * tt);
        }
    }
}
