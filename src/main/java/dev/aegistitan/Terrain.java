package dev.aegistitan;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** All ground damage from the Titan Cleaver, and putting it back afterwards. */
final class Terrain implements Listener {

    private record Change(Block block, BlockData original, BlockData placed) {
    }

    private static final BlockFace[] CHECK_FACES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final AegisTitan plugin;
    private final NamespacedKey debrisKey;
    private final List<Scar> pending = new ArrayList<>();
    private final Set<FallingBlock> debris = new HashSet<>();

    Terrain(AegisTitan plugin) {
        this.plugin = plugin;
        this.debrisKey = new NamespacedKey(plugin, "titan_debris");
    }

    /** Everything one slam changed, so it can be undone together. */
    final class Scar {
        private final List<Change> changes = new ArrayList<>();
        private final Set<Block> touched = new HashSet<>();
        /** Allowed to break ground under water (holes fill with water instead of air). */
        private final boolean waterMode;

        Scar(boolean waterMode) {
            this.waterMode = waterMode;
        }

        /** Remove a block (part of the cut). */
        boolean cut(Block b) {
            if (touched.contains(b) || !canModify(b, waterMode)) {
                return false;
            }
            touched.add(b);
            boolean wet = waterMode && touchesWater(b);
            set(b, (wet ? Material.WATER : Material.AIR).createBlockData());
            if (!wet) {
                clearSoftAbove(b);
            }
            return true;
        }

        /** Replace a surface block with a broken-looking one. */
        boolean crack(Block top, Random random) {
            if (touched.contains(top) || !canModify(top, waterMode)) {
                return false;
            }
            touched.add(top);
            clearSoftAbove(top);
            set(top, crackedVersion(top.getType(), random));
            return true;
        }

        /** Push a broken chunk up out of the ground on top of a block. */
        void raise(Block top, Random random) {
            Block above = top.getRelative(BlockFace.UP);
            if (above.getType().isAir() && !touched.contains(above)) {
                touched.add(above);
                set(above, crackedVersion(top.getType(), random));
            }
        }

        /** Call when the slam is done: starts the repair timer. */
        void finish() {
            int seconds = plugin.getConfig().getInt("axe.crater-restore-seconds", 30);
            if (seconds <= 0 || changes.isEmpty()) {
                pending.remove(this); // permanent (or nothing to undo)
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pending.contains(this)) {
                    restoreGradually(this);
                }
            }, seconds * 20L);
        }

        private void clearSoftAbove(Block top) {
            Block above = top.getRelative(BlockFace.UP);
            Material m = above.getType();
            if (!m.isAir() && !m.isSolid() && !above.isLiquid() && !touched.contains(above)
                    && !(above.getState(false) instanceof TileState)) {
                touched.add(above);
                boolean wet = waterMode && touchesWater(above);
                set(above, (wet ? Material.WATER : Material.AIR).createBlockData()); // seagrass etc. becomes water, not an air bubble
            }
        }

        private void set(Block block, BlockData data) {
            changes.add(new Change(block, block.getBlockData(), data));
            block.setBlockData(data, false);
        }
    }

    Scar newScar() {
        return newScar(false);
    }

    /** waterMode = also break the ground under water (used by the trident). */
    Scar newScar(boolean waterMode) {
        Scar scar = new Scar(waterMode);
        pending.add(scar);
        return scar;
    }

    private static boolean touchesWater(Block b) {
        for (BlockFace face : CHECK_FACES) {
            Block n = b.getRelative(face);
            if (b.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4) && n.getType() == Material.WATER) {
                return true;
            }
        }
        return false;
    }

    /** Top solid block of the column at (x, z), searching a little around y. */
    static Block surface(World world, double x, double z, double y) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null; // never force-load chunks just for effects
        }
        int start = (int) Math.floor(y) + 3;
        for (int by = start; by > start - 9 && by > world.getMinHeight(); by--) {
            Block b = world.getBlockAt(bx, by, bz);
            if (b.getType().isSolid()) {
                return b;
            }
        }
        return null;
    }

    private static boolean canModify(Block b, boolean waterMode) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid() || (m.hasGravity() && !waterMode)) {
            return false;
        }
        float hardness = m.getHardness();
        if (hardness < 0 || hardness >= 50) {
            return false; // bedrock, obsidian, barriers...
        }
        for (BlockFace face : CHECK_FACES) {
            Block n = b.getRelative(face);
            if (!b.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) {
                return false;
            }
            Material nm = n.getType();
            if (waterMode) {
                if (nm == Material.LAVA) {
                    return false; // never let lava pour in
                }
            } else if (n.isLiquid() || nm.hasGravity()) {
                return false; // never let water, lava, sand or gravel pour in
            }
        }
        return !(b.getState(false) instanceof TileState); // chests, furnaces, signs... never touch
    }

    static BlockData crackedVersion(Material m, Random random) {
        String n = m.name();
        Material[] pool;
        if (n.contains("DEEPSLATE")) {
            pool = new Material[]{Material.COBBLED_DEEPSLATE, Material.CRACKED_DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_BRICKS};
        } else if (m == Material.GRASS_BLOCK || n.contains("DIRT") || n.contains("MUD") || n.contains("MOSS")
                || m == Material.PODZOL || m == Material.MYCELIUM || m == Material.FARMLAND) {
            pool = new Material[]{Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.DIRT, Material.PACKED_MUD};
        } else if (n.contains("RED_SAND")) {
            pool = new Material[]{Material.RED_SANDSTONE, Material.CUT_RED_SANDSTONE};
        } else if (n.contains("SAND")) {
            pool = new Material[]{Material.SANDSTONE, Material.CUT_SANDSTONE};
        } else if (n.contains("NETHERRACK") || n.contains("NYLIUM") || n.contains("NETHER")) {
            pool = new Material[]{Material.NETHERRACK, Material.CRACKED_NETHER_BRICKS, Material.BLACKSTONE};
        } else if (n.contains("END_STONE")) {
            pool = new Material[]{Material.END_STONE, Material.END_STONE_BRICKS};
        } else if (n.contains("SNOW") || n.contains("ICE")) {
            pool = new Material[]{Material.SNOW_BLOCK, Material.PACKED_ICE};
        } else {
            pool = new Material[]{Material.COBBLESTONE, Material.CRACKED_STONE_BRICKS, Material.TUFF, Material.ANDESITE};
        }
        return pool[random.nextInt(pool.length)].createBlockData();
    }

    // ------------------------------------------------------------------ repairing

    private boolean restoreOne(Change c) {
        if (c.block().getBlockData().equals(c.placed())) {
            c.block().setBlockData(c.original(), false);
            return true;
        }
        return false;
    }

    /** Rebuild a scar a few thousand blocks per tick so huge cuts don't lag the server. */
    private void restoreGradually(Scar scar) {
        int perTick = Math.max(200, plugin.getConfig().getInt("axe.blocks-per-tick", 6000));
        List<Change> list = scar.changes;
        new BukkitRunnable() {
            private int index = list.size() - 1;
            private boolean first = true;
            private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            @Override
            public void run() {
                int budget = perTick;
                while (index >= 0 && budget-- > 0) {
                    Change c = list.get(index);
                    if (restoreOne(c)) {
                        Block b = c.block();
                        minX = Math.min(minX, b.getX());
                        minY = Math.min(minY, b.getY());
                        minZ = Math.min(minZ, b.getZ());
                        maxX = Math.max(maxX, b.getX());
                        maxY = Math.max(maxY, b.getY());
                        maxZ = Math.max(maxZ, b.getZ());
                        if (first) {
                            first = false;
                            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_ROOTED_DIRT_PLACE, 1.5f, 0.6f);
                        }
                        if (index % 25 == 0 && !c.original().getMaterial().isAir()) {
                            Vector p = b.getLocation().toVector().add(new Vector(0.5, 1.0, 0.5));
                            Fx.spawn(b.getWorld(), Particle.BLOCK, p, 3, 0.3, 0.1, 0.3, 0.05, c.original());
                        }
                    }
                    index--;
                }
                if (index < 0) {
                    cancel();
                    pending.remove(scar);
                    if (!list.isEmpty() && minX != Integer.MAX_VALUE) {
                        unbury(list.get(0).block().getWorld(), minX, minY, minZ, maxX, maxY, maxZ);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Anyone standing in the crater when it closes gets lifted out instead of stuck in blocks. */
    private static void unbury(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        double rx = (maxX - minX) / 2.0 + 2;
        double ry = (maxY - minY) / 2.0 + 3;
        double rz = (maxZ - minZ) / 2.0 + 2;
        for (Entity e : world.getNearbyEntities(center, rx, ry, rz)) {
            if (!(e instanceof LivingEntity)) {
                continue;
            }
            Location loc = e.getLocation();
            int lift = 0;
            while (lift < 300 && loc.getY() < world.getMaxHeight()
                    && (loc.getBlock().getType().isSolid() || loc.clone().add(0, 1, 0).getBlock().getType().isSolid())) {
                loc.add(0, 1, 0);
                lift++;
            }
            if (lift > 0) {
                e.teleport(loc);
            }
        }
    }

    /** Put everything back immediately (used when the server stops). */
    void restoreAll() {
        for (Scar scar : new ArrayList<>(pending)) {
            for (int i = scar.changes.size() - 1; i >= 0; i--) {
                restoreOne(scar.changes.get(i));
            }
        }
        pending.clear();
        for (FallingBlock fb : debris) {
            if (fb.isValid()) {
                fb.remove();
            }
        }
        debris.clear();
    }

    // ------------------------------------------------------------------ flying chunks of ground

    void launchDebris(World world, Vector at, BlockData data, Vector velocity) {
        debris.removeIf(fb -> !fb.isValid());
        if (debris.size() >= 150) {
            return;
        }
        FallingBlock fb = world.spawnFallingBlock(at.toLocation(world), data);
        fb.setDropItem(false);
        fb.setCancelDrop(true);
        fb.setHurtEntities(false);
        fb.getPersistentDataContainer().set(debrisKey, PersistentDataType.BYTE, (byte) 1);
        fb.setVelocity(velocity);
        debris.add(fb);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (fb.isValid()) {
                fb.remove();
            }
            debris.remove(fb);
        }, 80L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fb
                && fb.getPersistentDataContainer().has(debrisKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            Vector p = fb.getLocation().toVector();
            Fx.spawn(fb.getWorld(), Particle.BLOCK, p, 8, 0.25, 0.1, 0.25, 0.1, fb.getBlockData());
            fb.remove();
            debris.remove(fb);
        }
    }
}
