package dev.neilb.realmbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.raphimc.minecraftauth.extra.realms.model.RealmsJoinInformation;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;

import java.net.URI;
import java.util.List;

/**
 * The RealmBridge screen: sign in, accept invite codes, and one-click join
 * Bedrock Realms from Java Edition. Widget-only (no custom rendering) so it
 * survives Minecraft's render-pipeline churn.
 */
public final class RealmBridgeScreen extends Screen {

    private final Screen parent;
    private final RealmBridgeCore core;

    private volatile List<RealmsServer> realms;
    private volatile String deviceCodeUrl;
    private StringWidget statusWidget;
    private EditBox codeBox;
    private boolean busy;

    public RealmBridgeScreen(final Screen parent, final RealmBridgeCore core) {
        super(Component.literal("Bedrock Realms"));
        this.parent = parent;
        this.core = core;
    }

    @Override
    protected void init() {
        final int centerX = this.width / 2;
        int y = 30;

        this.addRenderableWidget(new StringWidget(centerX - 100, 10, 200, 12, this.title, this.font));

        this.statusWidget = this.addRenderableWidget(new StringWidget(centerX - 150, y, 300, 12,
                Component.literal(this.statusText()), this.font));
        y += 20;

        if (this.deviceCodeUrl != null) {
            this.addRenderableWidget(Button.builder(Component.literal("Open sign-in page"),
                    b -> Util.getPlatform().openUri(URI.create(this.deviceCodeUrl))).bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        } else if (!this.core.auth().isLoggedIn()) {
            this.addRenderableWidget(Button.builder(Component.literal("Sign in with Microsoft"),
                    b -> this.signIn()).bounds(centerX - 100, y, 200, 20).build());
            y += 24;
        } else if (this.realms == null) {
            if (!this.busy) {
                this.loadRealms();
            }
        } else {
            for (final RealmsServer realm : this.realms) {
                final boolean usable = realm.isCompatible() && !realm.isExpired();
                final Button button = Button.builder(
                        Component.literal(realm.getName() + (usable ? "" : " (unavailable)")),
                        b -> this.join(realm)).bounds(centerX - 100, y, 200, 20).build();
                button.active = usable && !this.busy;
                this.addRenderableWidget(button);
                y += 24;
            }
            y += 8;
            this.codeBox = this.addRenderableWidget(new EditBox(this.font, centerX - 100, y, 130, 20,
                    Component.literal("Realm code")));
            this.codeBox.setMaxLength(32);
            this.addRenderableWidget(Button.builder(Component.literal("Add code"),
                    b -> this.acceptCode()).bounds(centerX + 34, y, 66, 20).build());
            y += 24;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(centerX - 100, this.height - 28, 200, 20).build());
    }

    private String statusText() {
        if (this.deviceCodeUrl != null) return "Sign in in your browser, then come back here.";
        if (!this.core.auth().isLoggedIn()) return "Sign in once to see your Bedrock Realms.";
        if (this.busy) return "Working...";
        if (this.realms == null) return "Loading realms...";
        if (this.realms.isEmpty()) return "No realms yet - add an invite code below.";
        return "Pick a realm to join.";
    }

    private void status(final String text) {
        this.minecraft.execute(() -> {
            if (this.statusWidget != null) {
                this.statusWidget.setMessage(Component.literal(text));
            }
        });
    }

    private void refresh() {
        this.minecraft.execute(() -> {
            if (this.minecraft.screen == this) {
                this.rebuildWidgets();
            }
        });
    }

    private void signIn() {
        this.busy = true;
        this.core.async(() -> {
            this.core.auth().authManager(deviceCode -> {
                this.deviceCodeUrl = deviceCode.getVerificationUri();
                this.status("Enter code " + deviceCode.getUserCode() + " in your browser.");
                this.refresh();
            });
            this.deviceCodeUrl = null;
            this.busy = false;
            this.refresh();
        }, e -> {
            this.deviceCodeUrl = null;
            this.busy = false;
            this.status("Sign-in failed: " + e.getMessage());
        });
    }

    private void loadRealms() {
        this.busy = true;
        this.core.async(() -> {
            final BedrockRealmsService service = this.core.realms();
            this.realms = service.getWorlds();
            this.busy = false;
            this.refresh();
        }, e -> {
            this.busy = false;
            this.status("Could not load realms: " + e.getMessage());
        });
    }

    private void acceptCode() {
        final String code = this.codeBox == null ? "" : this.codeBox.getValue().trim();
        if (code.isEmpty()) {
            this.status("Enter a realm invite code first.");
            return;
        }
        this.busy = true;
        this.status("Accepting invite...");
        this.core.async(() -> {
            final RealmsServer realm = this.core.realms().acceptInvite(code);
            this.realms = this.core.realms().getWorlds();
            this.busy = false;
            this.status("Joined '" + realm.getName() + "'!");
            this.refresh();
        }, e -> {
            this.busy = false;
            this.status("Invite failed: " + e.getMessage());
        });
    }

    private void join(final RealmsServer realm) {
        this.busy = true;
        this.status("Waking '" + realm.getName() + "'...");
        this.core.async(() -> {
            this.core.runner().ensureInstalled(this::status);
            final int accountIndex = this.core.runner().ensureAccount(this.core.auth().serialized());
            final RealmsJoinInformation join = this.core.realms().joinWorld(realm);
            this.core.runner().setRealmFilter(realm.getName());
            this.status("Starting bridge...");
            this.core.runner().start(join.getAddress(), accountIndex);
            this.busy = false;
            this.minecraft.execute(() -> ConnectScreen.startConnecting(
                    this.parent, this.minecraft,
                    ServerAddress.parseString(ViaProxyRunner.BIND),
                    new ServerData(realm.getName() + " (Bedrock)", ViaProxyRunner.BIND, ServerData.Type.OTHER),
                    false, null));
        }, e -> {
            this.busy = false;
            this.status("Failed: " + e.getMessage());
        });
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

}
