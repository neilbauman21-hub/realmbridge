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
