package dev.neilb.realmbridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;

import java.util.List;

/**
 * RealmBridge entrypoint. Primary UX: the "Bedrock Realms" button on the
 * Multiplayer screen (opens {@link RealmBridgeScreen}). The /realmbridge
 * chat commands remain as a power-user alternative.
 */
public final class RealmBridgeClient implements ClientModInitializer {

    private final RealmBridgeCore core = new RealmBridgeCore();

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof JoinMultiplayerScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Bedrock Realms"),
                                b -> client.setScreen(new RealmBridgeScreen(screen, this.core)))
                        .bounds(scaledWidth - 105, 6, 100, 20).build());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("realmbridge")
                        .then(ClientCommands.literal("login").executes(ctx -> {
                            this.core.async(() -> {
                                this.core.auth().authManager(code -> this.chat(
                                        "Open " + code.getVerificationUri() + " and enter code: " + code.getUserCode()));
                                this.chat("Signed in.");
                            }, e -> this.chat("Error: " + e.getMessage()));
                            return 1;
                        }))
                        .then(ClientCommands.literal("code")
                                .then(ClientCommands.argument("invite", StringArgumentType.word()).executes(ctx -> {
                                    final String invite = StringArgumentType.getString(ctx, "invite");
                                    this.core.async(() -> {
                                        final RealmsServer realm = this.core.realms().acceptInvite(invite);
                                        this.chat("Joined realm: " + realm.getName());
                                    }, e -> this.chat("Error: " + e.getMessage()));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("play").executes(ctx -> {
                            this.playCommand(null);
                            return 1;
                        }).then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            this.playCommand(StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            this.core.runner().stop();
                            this.chat("Bridge stopped.");
                            return 1;
                        }))));
    }

    private void playCommand(final String name) {
        this.core.async(() -> {
            final BedrockRealmsService service = this.core.realms();
            final List<RealmsServer> worlds = service.getWorlds().stream()
                    .filter(w -> w.isCompatible() && !w.isExpired()).toList();
            final RealmsServer realm = name == null
                    ? (worlds.size() == 1 ? worlds.get(0) : null)
                    : worlds.stream().filter(w -> w.getName() != null
                            && w.getName().toLowerCase().contains(name.toLowerCase())).findFirst().orElse(null);
            if (realm == null) {
                this.chat("Pick a realm: " + worlds.stream().map(RealmsServer::getName).toList());
                return;
            }
            this.chat("Waking '" + realm.getName() + "'...");
            final RealmsJoinInformation join = service.joinWorld(realm);
            this.core.runner().setRealmFilter(realm.getName());
            this.chat("Starting bridge...");
            this.core.runner().start(join.getAddress());
            this.chat("Ready! Multiplayer -> Direct Connection -> " + ViaProxyRunner.BIND);
        }, e -> this.chat("Error: " + e.getMessage()));
    }

    private void chat(final String message) {
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addClientSystemMessage(Component.literal("[RealmBridge] " + message)));
    }

}
