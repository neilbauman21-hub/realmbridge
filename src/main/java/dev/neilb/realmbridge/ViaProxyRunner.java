package dev.neilb.realmbridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages a headless ViaProxy (cli mode) subprocess pointed at a Bedrock Realm.
 * Reuses the bedrock-realm-bridge install (patched jar, plugins, saved account).
 */
public final class ViaProxyRunner {

    public static final String BIND = "127.0.0.1:25568";

    private final Path installDir = Path.of(System.getProperty("user.home"), ".bedrock-realm-bridge");
    private volatile Process process;

    public boolean isInstalled() {
        return Files.exists(this.installDir.resolve("ViaProxy.jar"));
    }

    public boolean isRunning() {
        final Process p = this.process;
        return p != null && p.isAlive();
    }

    /** Persist the realm-name filter for the ViaProxyPlus auto-refresh plugin. */
    public void setRealmFilter(final String realmName) throws IOException {
        final Path filter = this.installDir.resolve("plugins").resolve("ViaProxyPlus").resolve("realm.txt");
        Files.createDirectories(filter.getParent());
        Files.writeString(filter, realmName, StandardCharsets.UTF_8);
    }

    /** Launches ViaProxy cli against the given NetherNet address (blocking until port is up; call off-thread). */
    public synchronized void start(final String netherNetAddress) throws Exception {
        if (this.isRunning()) {
            return;
        }
        final String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-jar", this.installDir.resolve("ViaProxy.jar").toString(), "cli",
                "--bind-address", BIND,
                "--target-address", "nethernet-rpc://" + netherNetAddress,
                "--target-version", "Bedrock " + BridgeAuth.BEDROCK_VERSION,
                "--auth-method", "ACCOUNT",
                "--minecraft-account-index", "1");
        builder.directory(this.installDir.toFile());
        builder.redirectOutput(this.installDir.resolve("logs").resolve("realmbridge-viaproxy.log").toFile());
        builder.redirectErrorStream(true);
        this.process = builder.start();

        // Wait for the proxy to bind (up to 60s); fail fast if the process dies.
        final long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!this.process.isAlive()) {
                throw new IllegalStateException("ViaProxy exited with code " + this.process.exitValue()
                        + " (see logs/realmbridge-viaproxy.log)");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 25568), 500);
                return; // port is up
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        this.stop();
        throw new IllegalStateException("ViaProxy did not open " + BIND + " within 60s");
    }

    public synchronized void stop() {
        final Process p = this.process;
        if (p != null) {
            p.destroy();
            this.process = null;
        }
    }

}
