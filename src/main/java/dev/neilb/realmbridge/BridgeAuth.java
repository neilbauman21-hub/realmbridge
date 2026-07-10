package dev.neilb.realmbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Microsoft/Xbox auth + Bedrock Realms API access for the bridge.
 * Tokens persist in config/realmbridge/auth.json and refresh automatically.
 */
public final class BridgeAuth {

    /** Bedrock game version reported to the Realms API. */
    public static final String BEDROCK_VERSION = "1.26.30";

    private static final Gson GSON = new Gson();
    private final HttpClient httpClient = MinecraftAuth.createHttpClient("RealmBridge");
    private final Path authFile = FabricLoader.getInstance().getConfigDir().resolve("realmbridge").resolve("auth.json");
    private volatile BedrockAuthManager authManager;

    public boolean isLoggedIn() {
        return this.authManager != null || Files.exists(this.authFile);
    }

    /** Loads persisted tokens or runs the device-code login (blocking; call off-thread). */
    public synchronized BedrockAuthManager authManager(final Consumer<MsaDeviceCode> deviceCodeCallback) throws Exception {
        if (this.authManager != null) {
            return this.authManager;
        }
        if (Files.exists(this.authFile)) {
            final JsonObject json = GSON.fromJson(Files.readString(this.authFile, StandardCharsets.UTF_8), JsonObject.class);
            this.authManager = BedrockAuthManager.fromJson(this.httpClient, "RealmBridge", json);
        } else {
            this.authManager = BedrockAuthManager.create(this.httpClient, "RealmBridge")
                    .login(DeviceCodeMsaAuthService::new, deviceCodeCallback);
        }
        this.authManager.getChangeListeners().add(this::save);
        this.save();
        return this.authManager;
    }

    public BedrockRealmsService realmsService(final Consumer<MsaDeviceCode> deviceCodeCallback) throws Exception {
        final BedrockAuthManager manager = this.authManager(deviceCodeCallback);
        return new BedrockRealmsService(this.httpClient, BEDROCK_VERSION, manager.getRealmsXstsToken());
    }

    public synchronized void logout() throws Exception {
        this.authManager = null;
        Files.deleteIfExists(this.authFile);
    }

    private void save() {
        try {
            Files.createDirectories(this.authFile.getParent());
            Files.writeString(this.authFile, GSON.toJson(BedrockAuthManager.toJson(this.authManager)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RealmBridgeCore.LOGGER.error("Failed to persist auth tokens", e);
        }
    }

}
