package com.ideomancer.machinemod.block.portal;

import com.ideomancer.machinemod.Ideomod;
import com.ideomancer.machinemod.config.IdeomodConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where an unlinked Oddstone Portal throws items and mobs (the Ideomancer's idea, 2026-11-20).
 *
 * Each thing thrown gets its own random spot, scatter_distance_min to _max blocks away, as before. If that
 * spot's chunk is loaded it lands there at once. If not, nothing is loaded for it: it waits in this list,
 * saved with the world, until that chunk loads (a player comes near, or the land is explored), and lands
 * then, on the ground there. A stream of things goes through at the rate it goes in and spreads thinly over
 * a huge area, instead of loading or generating new land for every one (which made a hopper or mob farm
 * emptying into an unlinked portal lag the server).
 *
 * Waiting chunks are checked once a second; what waits in a chunk that is running lands then.
 *
 * Like vanilla portal arrivals there is no cap: what lands only meets vanilla's own limits (items merge,
 * crowded mobs are hurt by entity cramming, far monsters despawn).
 *
 * Players are not listed: they are thrown at once, as before (the ground they land on is loaded anyway, as
 * they stand in it). Neither are thrown ender pearls: a pearl that waited would teleport its thrower
 * whenever its chunk finally loaded.
 *
 * A spot that turns out to be void (the End) gets another spot; after {@link #TRIES} tries the thing is put
 * back where it went in, as a throw with nowhere to land always was.
 *
 * Written by Claude, directed and guided by The Ideomancer.
 */
public class ScatterQueue extends SavedData {

    private static final String DATA_NAME = "machinemod_scatter_queue";
    private static final int VERSION = 1;
    /** Spots tried before a thrown thing with nowhere to land is put back where it went in. */
    public static final int TRIES = 8;

    /** A thing waiting for its chunk to load: where it lands, where it went in, how many spots it has tried. */
    public record Waiting(int x, int z, BlockPos origin, int tries, CompoundTag entity) {}

    private final Map<Long, List<Waiting>> byChunk = new HashMap<>();

    public static ScatterQueue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ScatterQueue::load, ScatterQueue::new, DATA_NAME);
    }

    // ========================================================================
    // THROWING
    // ========================================================================

    /**
     * Throws a thing that is not a player out of an unlinked portal. Returns false if it was not thrown
     * (it stays where it is): every loaded spot tried was void, or it can't be saved.
     */
    public static boolean throwOut(ServerLevel level, Entity entity) {
        BlockPos origin = entity.blockPosition();
        for (int attempt = 0; attempt < TRIES; attempt++) {
            BlockPos column = pickColumn(level, origin);
            if (!isRunning(level, ChunkPos.asLong(column.getX() >> 4, column.getZ() >> 4))) {
                // Not loaded (or not running): it waits there, and nothing is loaded for it.
                CompoundTag tag = new CompoundTag();
                if (!entity.save(tag)) return false;
                get(level).add(new Waiting(column.getX(), column.getZ(), origin.immutable(), attempt, tag));
                entity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
                return true;
            }
            BlockPos spot = landingSpot(level, column.getX(), column.getZ());
            if (spot == null) continue;   // void: try another spot
            Entity arrived = OddstonePortalBlock.moveThrough(level, entity, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    entity.getYRot(), entity.getXRot(), entity.getDeltaMovement());
            hurtOnArrival(level, arrived);
            return true;
        }
        Ideomod.LOGGER.info("{} not thrown: no ground found around {}", entity.getName().getString(), origin);
        return false;
    }

    /** A random column scatter_distance_min to _max blocks from {@code from}, in a random direction, inside the world border. */
    static BlockPos pickColumn(ServerLevel level, BlockPos from) {
        int minDist = IdeomodConfig.getScatterDistanceMin();
        int maxDist = IdeomodConfig.getScatterDistanceMax();
        WorldBorder border = level.getWorldBorder();
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        double distance = minDist + level.getRandom().nextDouble() * (maxDist - minDist);
        return border.clampToBounds(from.getX() + Math.cos(angle) * distance, from.getY(), from.getZ() + Math.sin(angle) * distance);
    }

    /**
     * Where to stand in a loaded column: on top of the highest block, or under a roof (the Nether) a
     * random place to stand below it (the roof itself only if there is none); null if the column is void.
     */
    @Nullable
    static BlockPos landingSpot(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (y <= level.getMinBuildHeight()) return null;
        if (!level.dimensionType().hasCeiling()) return new BlockPos(x, y, z);
        BlockPos inside = OddstonePortalBlock.randomPlaceToStand(level, x, z);
        return inside != null ? inside : new BlockPos(x, y, z);
    }

    /** Only living things take the scatter damage: an item or a minecart would be destroyed by it. */
    private static void hurtOnArrival(ServerLevel level, @Nullable Entity arrived) {
        if (arrived instanceof LivingEntity) arrived.hurt(level.damageSources().magic(), IdeomodConfig.getScatterDamage());
    }

    // ========================================================================
    // THE LIST
    // ========================================================================

    public void add(Waiting w) {
        byChunk.computeIfAbsent(ChunkPos.asLong(w.x() >> 4, w.z() >> 4), k -> new ArrayList<>()).add(w);
        setDirty();
    }

    /** Everything waiting, in no particular order (for tests and commands). */
    public List<Waiting> all() {
        List<Waiting> out = new ArrayList<>();
        byChunk.values().forEach(out::addAll);
        return out;
    }

    /** Puts down everything waiting in this chunk (it must be loaded). */
    public void deliver(ServerLevel level, long chunk) {
        List<Waiting> here = byChunk.remove(chunk);
        if (here == null) return;
        setDirty();
        for (Waiting w : here) {
            if (w.tries() >= TRIES) {
                place(level, w, w.origin(), false);   // its way back: where it went in, exactly
                continue;
            }
            BlockPos spot = landingSpot(level, w.x(), w.z());
            if (spot == null) {
                // Void. Another spot, or after the last try back where it went in; it waits for that
                // chunk too (delivered on a later check once it is running).
                boolean last = w.tries() + 1 >= TRIES;
                BlockPos column = last ? w.origin() : pickColumn(level, w.origin());
                add(new Waiting(column.getX(), column.getZ(), w.origin(), last ? TRIES : w.tries() + 1, w.entity()));
                continue;
            }
            place(level, w, spot, true);
        }
    }

    private static void place(ServerLevel level, Waiting w, BlockPos at, boolean hurt) {
        Entity e = EntityType.loadEntityRecursive(w.entity(), level, ent -> {
            ent.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, ent.getYRot(), ent.getXRot());
            return ent;
        });
        if (e == null) {
            Ideomod.LOGGER.warn("A thrown thing waiting at {} could not be read back; dropped", at);
            return;
        }
        if (!level.tryAddFreshEntityWithPassengers(e)) {
            Ideomod.LOGGER.warn("{} waiting at {} is already in the world; not added again", e.getName().getString(), at);
            return;
        }
        if (hurt) hurtOnArrival(level, e);
    }

    // ========================================================================
    // DELIVERY
    // ========================================================================

    /** How often the list looks at its waiting chunks, in ticks. */
    private static final int CHECK_EVERY = 20;

    /**
     * Once a second: anything waiting in a chunk that is now running (entities ticking, as round a player)
     * lands. A lookup per waiting chunk; it also picks things up after a restart, with no events to miss.
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % CHECK_EVERY != 0) return;
        ScatterQueue queue = get(level);
        if (queue.byChunk.isEmpty()) return;
        List<Long> ready = new ArrayList<>();
        for (long chunk : queue.byChunk.keySet()) {
            if (isRunning(level, chunk)) ready.add(chunk);
        }
        for (long chunk : ready) queue.deliver(level, chunk);
    }

    /** True if the chunk is loaded with its entities ticking (things put there come to life at once). */
    static boolean isRunning(ServerLevel level, long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        return level.getChunkSource().getChunkNow(cx, cz) != null
                && level.isPositionEntityTicking(new BlockPos((cx << 4) + 8, level.getMinBuildHeight(), (cz << 4) + 8));
    }

    // ========================================================================
    // SAVING
    // ========================================================================

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Version", VERSION);
        ListTag list = new ListTag();
        for (List<Waiting> ws : byChunk.values()) {
            for (Waiting w : ws) {
                CompoundTag t = new CompoundTag();
                t.putInt("X", w.x());
                t.putInt("Z", w.z());
                t.putInt("OX", w.origin().getX());
                t.putInt("OY", w.origin().getY());
                t.putInt("OZ", w.origin().getZ());
                t.putInt("Tries", w.tries());
                t.put("Entity", w.entity());
                list.add(t);
            }
        }
        tag.put("Waiting", list);
        return tag;
    }

    public static ScatterQueue load(CompoundTag tag) {
        ScatterQueue q = new ScatterQueue();
        ListTag list = tag.getList("Waiting", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            q.byChunk.computeIfAbsent(ChunkPos.asLong(t.getInt("X") >> 4, t.getInt("Z") >> 4), k -> new ArrayList<>())
                    .add(new Waiting(t.getInt("X"), t.getInt("Z"), new BlockPos(t.getInt("OX"), t.getInt("OY"), t.getInt("OZ")),
                            t.getInt("Tries"), t.getCompound("Entity")));
        }
        return q;
    }
}
