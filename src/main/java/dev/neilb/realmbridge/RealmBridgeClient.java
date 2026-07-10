package dev.neilb.realmbridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /realmbridge - join Bedrock Realms from Java Edition.
 *
 *   /realmbridge login          one-time Microsoft sign-in (device code in chat)
 *   /realmbridge code <invite>  accept a realm invite code onto your account
 *   /realmbridge play [name]    resolve the realm, launch the bridge, connect
 *   /realmbridge stop           stop the bridge
 */
public final class RealmBridgeClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("realmbridge");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "RealmBridge-Worker");
        t.setDaemon(true);
        return t;
    });
    private final BridgeAuth auth = new BridgeAuth();
    private final ViaProxyRunner runner = new ViaProxyRunner();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("realmbridge")
                        .then(ClientCommands.literal("login").executes(ctx -> {
                            this.async(this::login);
                            return 1;
                        }))
                        .then(ClientCommands.literal("code")
                                .then(ClientCommands.argument("invite", StringArgumentType.word()).executes(ctx -> {
                                    final String invite = StringArgumentType.getString(ctx, "invite");
                                    this.async(() -> this.acceptCode(invite));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("play").executes(ctx -> {
                            this.async(() -> this.play(null));
                            return 1;
                        }).then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            final String name = StringArgumentType.getString(ctx, "name");
                            this.async(() -> this.play(name));
                            return 1;
                        })))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            this.runner.stop();
                            this.chat("Bridge stopped.");
                            return 1;
                        }))));
    }

    private void login() throws Exception {
        this.chat("Signing in to Microsoft...");
        this.auth.authManager(deviceCode -> this.chat(
                "Open " + deviceCode.getVerificationUri() + " and enter code: " + deviceCode.getUserCode()));
        this.chat("Signed in. Use /realmbridge code <invite> or /realmbridge play");
    }

    private void acceptCode(final String invite) throws Exception {
        final BedrockRealmsService service = this.realms();
        final RealmsServer realm = service.acceptInvite(invite);
        this.chat("Joined realm: " + realm.getName() + ". Use /realmbridge play");
    }

    private void play(final String name) throws Exception {
        if (!this.runner.isInstalled()) {
            this.chat("bedrock-realm-bridge install not found (~/.bedrock-realm-bridge). Run its setup once first.");
            return;
        }
        final BedrockRealmsService service = this.realms();
        final List<RealmsServer> worlds = service.getWorlds().stream()
                .filter(w -> w.isCompatible() && !w.isExpired()).toList();
        if (worlds.isEmpty()) {
            this.chat("No realms on this account. Accept an invite first: /realmbridge code <invite>");
            return;
        }
        final RealmsServer realm;
        if (name != null) {
            realm = worlds.stream().filter(w -> w.getName() != null && w.getName().toLowerCase().contains(name.toLowerCase()))
                    .findFirst().orElse(null);
            if (realm == null) {
                this.chat("No realm matching '" + name + "'. Available: " + worlds.stream().map(RealmsServer::getName).toList());
                return;
            }
        } else if (worlds.size() == 1) {
            realm = worlds.get(0);
        } else {
            this.chat("Multiple realms - pick one: " + worlds.stream().map(RealmsServer::getName).toList()
                    + " (/realmbridge play <name>)");
            return;
        }
        this.chat("Waking '" + realm.getName() + "'...");
        final RealmsJoinInformation join = service.joinWorld(realm);
        this.runner.setRealmFilter(realm.getName());
        this.chat("Starting bridge...");
        this.runner.start(join.getAddress());
        this.chat("Ready! Multiplayer -> Direct Connection -> " + ViaProxyRunner.BIND);
    }

    private BedrockRealmsService realms() throws Exception {
        return this.auth.realmsService(deviceCode -> this.chat(
                "Open " + deviceCode.getVerificationUri() + " and enter code: " + deviceCode.getUserCode()));
    }

    private void async(final ThrowingRunnable task) {
        this.worker.submit(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                LOGGER.error("RealmBridge task failed", e);
                this.chat("Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private void chat(final String message) {
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addClientSystemMessage(Component.literal("[RealmBridge] " + message)));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
