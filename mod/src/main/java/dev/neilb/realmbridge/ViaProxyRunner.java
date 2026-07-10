package dev.neilb.realmbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Manages the headless ViaProxy bridge. Fully self-bootstrapping: downloads
 * the patched ViaProxy + ViaProxyPlus plugin from the RealmBridge release if
 * missing, preseeds config, and injects the mod's Microsoft sign-in as the
 * ViaProxy account (no ViaProxy GUI, ever).
 */
public final class ViaProxyRunner {

    public static final String BIND = "127.0.0.1:25568";
    private static final String RELEASE_BASE = "https://github.com/neilbauman21-hub/realmbridge/releases/latest/download/";
    private static final String BEDROCK_ACCOUNT_TYPE = "net.raphimc.viaproxy.saves.impl.accounts.BedrockAccount";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path installDir = Path.of(System.getProperty("user.home"), ".bedrock-realm-bridge");
    private volatile Process process;

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

    /** Downloads the bridge components from the RealmBridge release if missing. */
    public void ensureInstalled(final Consumer<String> status) throws Exception {
        Files.createDirectories(this.installDir.resolve("plugins"));
        final Path jar = this.installDir.resolve("ViaProxy.jar");
        if (!Files.exists(jar)) {
            status.accept("Downloading bridge (~45 MB, one time)...");
            this.download(RELEASE_BASE + "ViaProxy-patched.jar", jar);
        }
        final Path plugin = this.installDir.resolve("plugins").resolve("ViaProxyPlus.jar");
        if (!Files.exists(plugin)) {
            status.accept("Downloading bridge plugin...");
            this.download(RELEASE_BASE + "ViaProxyPlus.jar", plugin);
        }
        final Path vbConfig = this.installDir.resolve("viabedrock.yml");
        if (!Files.exists(vbConfig)) {
            Files.writeString(vbConfig, "enable-experimental-features: true\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Injects the mod's sign-in into ViaProxy's account store if it has no
     * Bedrock account yet. Returns the 0-based index of the Bedrock account
     * (ViaProxy's --minecraft-account-index is 0-indexed).
     */
    public int ensureAccount(final JsonObject serializedAuth) throws IOException {
        final Path savesFile = this.installDir.resolve("saves.json");
        final JsonObject root = Files.exists(savesFile)
                ? GSON.fromJson(Files.readString(savesFile, StandardCharsets.UTF_8), JsonObject.class)
                : new JsonObject();
        final JsonArray accounts = root.has("accountsV4") ? root.getAsJsonArray("accountsV4") : new JsonArray();
        for (int i = 0; i < accounts.size(); i++) {
            final JsonObject account = accounts.get(i).getAsJsonObject();
            if (BEDROCK_ACCOUNT_TYPE.equals(account.has("accountType") ? account.get("accountType").getAsString() : "")) {
                return i;
            }
        }
        if (serializedAuth == null) {
            throw new IllegalStateException("Not signed in");
        }
        final JsonObject entry = serializedAuth.deepCopy();
        entry.addProperty("accountType", BEDROCK_ACCOUNT_TYPE);
        accounts.add(entry);
        root.add("accountsV4", accounts);
        Files.writeString(savesFile, GSON.toJson(root), StandardCharsets.UTF_8);
        return accounts.size() - 1;
    }

    /** Launches ViaProxy cli (blocking until the port is up; call off-thread). */
    public synchronized void start(final String netherNetAddress, final int accountIndex) throws Exception {
        if (this.isRunning()) {
            return;
        }
        final String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.createDirectories(this.installDir.resolve("logs"));
        final ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-jar", this.installDir.resolve("ViaProxy.jar").toString(), "cli",
                "--bind-address", BIND,
                "--target-address", "nethernet-rpc://" + netherNetAddress,
                "--target-version", "Bedrock " + BridgeAuth.BEDROCK_VERSION,
                "--auth-method", "ACCOUNT",
                "--minecraft-account-index", String.valueOf(accountIndex));
        builder.directory(this.installDir.toFile());
        builder.redirectOutput(this.installDir.resolve("logs").resolve("realmbridge-viaproxy.log").toFile());
        builder.redirectErrorStream(true);
        this.process = builder.start();

        final long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!this.process.isAlive()) {
                throw new IllegalStateException("ViaProxy exited with code " + this.process.exitValue()
                        + " (see logs/realmbridge-viaproxy.log)");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 25568), 500);
                return;
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

    private void download(final String url, final Path target) throws Exception {
        final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        final HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed (" + response.statusCode() + "): " + url);
        }
        final Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

}
