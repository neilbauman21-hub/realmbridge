package dev.neilb.viaproxyplus;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.plugins.events.ConsoleCommandEvent;
import net.raphimc.viaproxy.plugins.events.ViaProxyLoadedEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ViaProxyPlus - "vanilla+" quality of life patches for playing Bedrock Realms
 * through ViaProxy/ViaBedrock with a Java client.
 *
 * Features:
 *  - Periodic player inventory resync (works around ViaBedrock's experimental
 *    inventory tracking missing item pickup updates).
 *  - "resync" console command to force an immediate inventory resync.
 *  - Mutes known-harmless ViaBedrock warning spam (missing/unmapped block
 *    states) after the first few occurrences so real errors stay visible.
 */
public class ViaProxyPlusPlugin extends ViaProxyPlugin {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    private static final long RESYNC_INTERVAL_SECONDS = 10;

    private static final String[] MUTED_WARNING_PREFIXES = {
            "Missing waterlogged block state",
            "Invalid layer 2 block state",
            "Received packet", // "... outside PLAY state. Ignoring it." join noise
    };
    private static final int MUTED_WARNING_LIMIT = 5; // per message prefix

    private ScheduledExecutorService scheduler;

    @Override
    public void onEnable() {
        ViaProxy.EVENT_MANAGER.register(this);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "ViaProxyPlus-Resync");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(
                this::resyncAllInventories,
                RESYNC_INTERVAL_SECONDS, RESYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOGGER.log(Level.INFO, "ViaProxyPlus enabled: inventory resync every "
                + RESYNC_INTERVAL_SECONDS + "s, 'resync' console command, warn-spam filter.");
    }

    @EventHandler
    public void onViaProxyLoaded(final ViaProxyLoadedEvent event) {
        // ViaBedrock's platform only exists once the protocol translator is up,
        // so the log filter can't be installed from onEnable.
        this.installWarningSpamFilter();
    }

    @Override
    public void onDisable() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
    }

    @EventHandler
    public void onConsoleCommand(final ConsoleCommandEvent event) {
        if (event.getCommand().equalsIgnoreCase("resync")) {
            event.setCancelled(true);
            final int count = this.resyncAllInventories();
            LOGGER.log(Level.INFO, "Forced inventory resync for " + count + " player(s).");
        }
    }

    /**
     * Pushes ViaBedrock's tracked player inventory state to every connected
     * Java client. The tracker state is authoritative (it follows the Bedrock
     * server); the Java client's rendered inventory is what drifts.
     *
     * Returns the number of connections resynced.
     */
    private int resyncAllInventories() {
        int count = 0;
        try {
            if (Via.getManager() == null) {
                return 0;
            }
            for (final UserConnection user : Via.getManager().getConnectionManager().getConnections()) {
                final InventoryTracker tracker = user.get(InventoryTracker.class);
                if (tracker == null) {
                    continue; // not a Bedrock session
                }
                // Don't clobber an open chest/furnace UI mid-interaction.
                if (tracker.getCurrentContainer() != null || tracker.getPendingCloseContainer() != null) {
                    continue;
                }
                final io.netty.channel.Channel channel = user.getChannel();
                if (channel == null || !channel.isActive()) {
                    continue;
                }
                channel.eventLoop().execute(() -> {
                    try {
                        PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
                    } catch (Throwable e) {
                        LOGGER.log(Level.FINE, "Inventory resync failed for a connection", e);
                    }
                });
                count++;
            }
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Inventory resync sweep failed", e);
        }
        return count;
    }

    /**
     * ViaBedrock warns once per unmapped block state occurrence, which floods
     * the log on 1.26.x worlds. Let the first few through (so the issue stays
     * discoverable), then drop the rest.
     */
    private void installWarningSpamFilter() {
        final Logger viaBedrockLogger = Logger.getLogger(ViaBedrock.getPlatform().getLogger().getName());
        final int[] counts = new int[MUTED_WARNING_PREFIXES.length];
        final java.util.logging.Filter previous = viaBedrockLogger.getFilter();
        viaBedrockLogger.setFilter(record -> {
            if (previous != null && !previous.isLoggable(record)) {
                return false;
            }
            final String message = record.getMessage();
            if (message != null && record.getLevel().intValue() <= Level.WARNING.intValue()) {
                for (int i = 0; i < MUTED_WARNING_PREFIXES.length; i++) {
                    if (message.startsWith(MUTED_WARNING_PREFIXES[i])) {
                        if (++counts[i] == MUTED_WARNING_LIMIT) {
                            record.setMessage(message + " (further '"
                                    + MUTED_WARNING_PREFIXES[i] + "' warnings muted)");
                            return true;
                        }
                        return counts[i] < MUTED_WARNING_LIMIT;
                    }
                }
            }
            return true;
        });
    }

}
