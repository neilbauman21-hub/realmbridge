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

    // Bedrock legacy-inventory pickup emulation. On Realms, item pickups send
    // TAKE_ITEM_ENTITY plus a WorldInteraction-only transaction - the client is
    // expected to insert the item into its own inventory locally. Real Bedrock
    // clients do; we emulate the same insertion (merge stacks, then first empty
    // slot, hotbar first). Server-side full content pushes correct any drift.
    private static final java.util.concurrent.ConcurrentHashMap<UserConnection, java.util.concurrent.ConcurrentHashMap<Long, net.raphimc.viabedrock.protocol.model.BedrockItem>> ITEM_ENTITIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void rememberItemEntity(final UserConnection user, final long runtimeId, final net.raphimc.viabedrock.protocol.model.BedrockItem item) {
        if (item == null || item.isEmpty()) return;
        final java.util.concurrent.ConcurrentHashMap<Long, net.raphimc.viabedrock.protocol.model.BedrockItem> map =
                ITEM_ENTITIES.computeIfAbsent(user, u -> new java.util.concurrent.ConcurrentHashMap<>());
        if (map.size() > 512) map.clear();
        map.put(runtimeId, item);
    }

    public static net.raphimc.viabedrock.protocol.model.BedrockItem takeItemEntity(final UserConnection user, final long runtimeId) {
        final java.util.concurrent.ConcurrentHashMap<Long, net.raphimc.viabedrock.protocol.model.BedrockItem> map = ITEM_ENTITIES.get(user);
        return map == null ? null : map.remove(runtimeId);
    }

    public static void emulatePickup(final UserConnection user, final net.raphimc.viabedrock.protocol.model.BedrockItem picked) {
        try {
            final InventoryTracker tracker = user.get(InventoryTracker.class);
            if (tracker == null) return;
            final net.raphimc.viabedrock.api.model.container.Container inv = tracker.getInventoryContainer();
            int remaining = picked.amount();
            for (int slot = 0; slot < inv.size() && remaining > 0; slot++) { // merge pass (slots 0-8 = hotbar first)
                final net.raphimc.viabedrock.protocol.model.BedrockItem cur = inv.getItem(slot);
                if (!cur.isEmpty() && cur.identifier() == picked.identifier() && cur.data() == picked.data()
                        && cur.tag() == null && picked.tag() == null && cur.amount() < 64) {
                    final int add = Math.min(64 - cur.amount(), remaining);
                    final net.raphimc.viabedrock.protocol.model.BedrockItem upd = (net.raphimc.viabedrock.protocol.model.BedrockItem) cur.copy();
                    upd.setAmount(cur.amount() + add);
                    inv.setItem(slot, upd);
                    remaining -= add;
                }
            }
            for (int slot = 0; slot < inv.size() && remaining > 0; slot++) { // first-empty pass
                if (inv.getItem(slot).isEmpty()) {
                    final net.raphimc.viabedrock.protocol.model.BedrockItem ni = (net.raphimc.viabedrock.protocol.model.BedrockItem) picked.copy();
                    ni.setAmount(remaining);
                    inv.setItem(slot, ni);
                    remaining = 0;
                }
            }
            net.raphimc.viabedrock.ViaBedrock.getPlatform().getLogger().log(java.util.logging.Level.INFO,
                    "[VP+ diag] pickup emulated: item=" + picked.identifier() + " x" + picked.amount()
                            + (remaining > 0 ? " (" + remaining + " didn't fit)" : ""));
            PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        } catch (Throwable e) {
            net.raphimc.viabedrock.ViaBedrock.getPlatform().getLogger().log(java.util.logging.Level.WARNING, "[VP+] pickup emulation failed", e);
        }
    }

    // Knockback synthesis: remember the last big correction per connection so
    // storms of corrections can be converted into one velocity arc.
    public static final class LastCorrection {
        public long wallTime;
        public long tick;
        public float x, y, z;
    }

    private static final java.util.concurrent.ConcurrentHashMap<UserConnection, LastCorrection> LAST_CORRECTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static LastCorrection lastCorrection(final UserConnection user) {
        return LAST_CORRECTIONS.computeIfAbsent(user, u -> new LastCorrection());
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
