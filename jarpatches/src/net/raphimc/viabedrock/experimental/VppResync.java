package net.raphimc.viabedrock.experimental;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.State;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.concurrent.TimeUnit;

/**
 * ViaProxyPlus helper: pushes the tracked player inventory to the Java client
 * shortly after a block break/place, so inventory changes feel vanilla instead
 * of waiting for the next periodic resync. The delay lets the server's own
 * slot updates arrive and apply to the tracker first.
 */
public final class VppResync {

    private VppResync() {
    }

    // Diagnostic: track recently player-broken block positions per connection so
    // the UPDATE_BLOCK handler can detect server-side break reverts.
    private static final java.util.concurrent.ConcurrentHashMap<UserConnection, java.util.concurrent.ConcurrentHashMap<Long, Long>> RECENT_BREAKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long packPosition(final com.viaversion.viaversion.api.minecraft.BlockPosition pos) {
        return ((long) (pos.x() & 0x3FFFFFF) << 38) | ((long) (pos.z() & 0x3FFFFFF) << 12) | (pos.y() & 0xFFF);
    }

    public static void noteBroken(final UserConnection user, final com.viaversion.viaversion.api.minecraft.BlockPosition pos) {
        final java.util.concurrent.ConcurrentHashMap<Long, Long> map = RECENT_BREAKS.computeIfAbsent(user, u -> new java.util.concurrent.ConcurrentHashMap<>());
        final long now = System.currentTimeMillis();
        map.put(packPosition(pos), now);
        if (map.size() > 64) {
            map.entrySet().removeIf(e -> now - e.getValue() > 10_000);
        }
    }

    // Per-tick client position history, used to apply server movement
    // corrections as a delta against where the client actually was at that
    // tick instead of hard-teleporting to a stale position.
    private static final java.util.concurrent.ConcurrentHashMap<UserConnection, java.util.concurrent.ConcurrentSkipListMap<Long, float[]>> TICK_POSITIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordTickPosition(final UserConnection user, final long tick, final float x, final float y, final float z) {
        final java.util.concurrent.ConcurrentSkipListMap<Long, float[]> map =
                TICK_POSITIONS.computeIfAbsent(user, u -> new java.util.concurrent.ConcurrentSkipListMap<>());
        map.put(tick, new float[]{x, y, z});
        while (map.size() > 200) {
            map.pollFirstEntry();
        }
    }

    public static float[] getTickPosition(final UserConnection user, final long tick) {
        final java.util.concurrent.ConcurrentSkipListMap<Long, float[]> map = TICK_POSITIONS.get(user);
        return map == null ? null : map.get(tick);
    }

    // Movement-correction deadband: suppress small server rewinds so the client
    // isn't visibly yanked, but periodically let one through to re-anchor, and
    // always apply large ones.
    private static final double CORRECTION_DEADBAND = 1.5;
    private static final int MAX_SUPPRESSED_PER_WINDOW = 25;
    private static long suppressWindowStart;
    private static int suppressCount;

    public static synchronized boolean shouldSuppressCorrection(final double dist) {
        final long now = System.currentTimeMillis();
        if (now - suppressWindowStart > 5_000) {
            suppressWindowStart = now;
            suppressCount = 0;
        }
        if (dist >= CORRECTION_DEADBAND) {
            suppressCount = 0;
            return false;
        }
        if (++suppressCount > MAX_SUPPRESSED_PER_WINDOW) {
            suppressCount = 0;
            return false;
        }
        return true;
    }

    /** Milliseconds since this position was broken by the player, or -1 if not recent. */
    public static long brokenAgoMillis(final UserConnection user, final com.viaversion.viaversion.api.minecraft.BlockPosition pos) {
        final java.util.concurrent.ConcurrentHashMap<Long, Long> map = RECENT_BREAKS.get(user);
        if (map == null) return -1;
        final Long ts = map.get(packPosition(pos));
        if (ts == null) return -1;
        final long ago = System.currentTimeMillis() - ts;
        return ago <= 10_000 ? ago : -1;
    }

    public static void scheduleInventoryResync(final UserConnection user) {
        final io.netty.channel.Channel channel = user.getChannel();
        if (channel == null || !channel.isActive()) {
            return;
        }
        channel.eventLoop().schedule(() -> {
            try {
                if (!channel.isActive()) return;
                if (user.getProtocolInfo().getServerState() != State.PLAY) return;
                final InventoryTracker tracker = user.get(InventoryTracker.class);
                if (tracker == null) return;
                if (tracker.getCurrentContainer() != null || tracker.getPendingCloseContainer() != null) return;
                PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
            } catch (Throwable ignored) {
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

}
