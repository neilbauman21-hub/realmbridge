package dev.neilb.viaproxyplus;

import dev.kastle.netty.channel.nethernet.config.NetherNetAddress;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.events.PreConnectEvent;
import net.raphimc.viaproxy.saves.impl.accounts.Account;
import net.raphimc.viaproxy.saves.impl.accounts.BedrockAccount;
import net.raphimc.viaproxy.util.NetherNetJsonRpcAddress;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Re-resolves the target realm on every Java client login. Bedrock Realms get
 * a fresh NetherNet session id every time the realm server wakes, so a stored
 * address goes stale whenever the realm sleeps — this makes connecting "just
 * work" without re-clicking Join in the Realms tab.
 *
 * The realm is chosen by a persisted name filter (console command:
 * {@code realm <name>}), or automatically when the account has exactly one.
 */
public final class RealmAutoRefresh {

    private static final Logger LOGGER = Logger.getLogger("ViaProxyPlus");
    private static final long CACHE_MILLIS = 15_000;

    private final File configFile;
    private volatile String realmNameFilter = "";
    private volatile String cachedAddress;
    private volatile long cachedAt;

    public RealmAutoRefresh(final File dataFolder) {
        this.configFile = new File(dataFolder, "realm.txt");
        try {
            if (this.configFile.exists()) {
                this.realmNameFilter = Files.readString(this.configFile.toPath(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read realm.txt", e);
        }
    }

    public String getRealmNameFilter() {
        return this.realmNameFilter;
    }

    public void setRealmNameFilter(final String filter) {
        this.realmNameFilter = filter == null ? "" : filter.trim();
        this.cachedAddress = null;
        try {
            Files.writeString(this.configFile.toPath(), this.realmNameFilter, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not persist realm.txt", e);
        }
    }

    @EventHandler
    public void onPreConnect(final PreConnectEvent event) {
        if (!(event.getServerAddress() instanceof NetherNetAddress)) {
            return; // not a NetherNet (realm/LAN) target
        }
        if (event.getIntendedState().getConnectionState() != ConnectionState.LOGIN) {
            return; // don't resolve for server-list pings
        }

        final long now = System.currentTimeMillis();
        final String cached = this.cachedAddress;
        if (cached != null && now - this.cachedAt < CACHE_MILLIS) {
            event.setServerAddress(new NetherNetJsonRpcAddress(cached));
            return;
        }

        final Account account = ViaProxy.getConfig().getAccount();
        if (!(account instanceof BedrockAccount bedrockAccount)) {
            return; // no Bedrock account selected; leave address untouched
        }

        try {
            final BedrockRealmsService service = new BedrockRealmsService(
                    MinecraftAuth.createHttpClient(),
                    ProtocolConstants.BEDROCK_VERSION_NAME,
                    bedrockAccount.getAuthManager().getRealmsXstsToken());

            final List<RealmsServer> worlds = service.getWorldsAsync().join();
            final RealmsServer realm = this.pickRealm(worlds);
            if (realm == null) {
                LOGGER.log(Level.WARNING, "[VP+] Realm auto-refresh: no matching realm (filter='"
                        + this.realmNameFilter + "', available: " + worlds.stream().map(RealmsServer::getName).toList()
                        + "). Set one with console command: realm <name>");
                return; // pass the original address through
            }

            final RealmsJoinInformation join = service.joinWorldAsync(realm).join();
            if (RealmsJoinInformation.PROTOCOL_NETHERNET_JSONRPC.equals(join.getNetworkProtocol())
                    || RealmsJoinInformation.PROTOCOL_NETHERNET.equals(join.getNetworkProtocol())) {
                this.cachedAddress = join.getAddress();
                this.cachedAt = now;
                event.setServerAddress(new NetherNetJsonRpcAddress(join.getAddress()));
                LOGGER.log(Level.INFO, "[VP+] Realm '" + realm.getName() + "' resolved fresh: " + join.getAddress());
            } else {
                LOGGER.log(Level.WARNING, "[VP+] Realm uses unexpected protocol " + join.getNetworkProtocol() + "; not refreshing.");
            }
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[VP+] Realm auto-refresh failed: " + rootMessage(e));
            event.setCancelMessage("§eRealm is waking up or unreachable §7(" + rootMessage(e) + ")§e — try again in ~20s.");
            event.setCancelled(true);
        }
    }

    private RealmsServer pickRealm(final List<RealmsServer> worlds) {
        final List<RealmsServer> usable = worlds.stream()
                .filter(w -> w.isCompatible() && !w.isExpired())
                .toList();
        if (!this.realmNameFilter.isEmpty()) {
            final String needle = this.realmNameFilter.toLowerCase();
            return usable.stream()
                    .filter(w -> w.getName() != null && w.getName().toLowerCase().contains(needle))
                    .findFirst().orElse(null);
        }
        return usable.size() == 1 ? usable.get(0) : null;
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        final String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

}
