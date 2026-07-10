package dev.neilb.realmbridge;

import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shared services: auth, bridge process, background worker. */
public final class RealmBridgeCore {

    public static final Logger LOGGER = LoggerFactory.getLogger("realmbridge");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "RealmBridge-Worker");
        t.setDaemon(true);
        return t;
    });
    private final BridgeAuth auth = new BridgeAuth();
    private final ViaProxyRunner runner = new ViaProxyRunner();

    public BridgeAuth auth() {
        return this.auth;
    }

    public ViaProxyRunner runner() {
        return this.runner;
    }

    /** Realms service; runs the device-code flow if not signed in (call off-thread). */
    public BedrockRealmsService realms() throws Exception {
        return this.auth.realmsService(code -> LOGGER.info("Device code: {} at {}", code.getUserCode(), code.getVerificationUri()));
    }

    public void async(final ThrowingRunnable task, final Consumer<Throwable> onError) {
        this.worker.submit(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                LOGGER.error("RealmBridge task failed", e);
                onError.accept(e);
            }
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

}
