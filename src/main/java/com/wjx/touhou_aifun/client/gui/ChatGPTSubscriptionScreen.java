package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsLLMSiteScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.OpenAIConfigMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncAISitesMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTReasoningSettings;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage;
import com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string;
import static com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage.Action;

/** The server publishes public account metadata only; credentials stay on the server. */
public final class ChatGPTSubscriptionScreen extends Screen {
    private static final int VISIBLE_MODELS = 2;
    private final AIChatSettingsLLMSiteScreen parent;
    private final UUID screenId = UUID.randomUUID();
    private JsonObject metadata = new JsonObject();
    private final List<JsonObject> accounts = new ArrayList<>();
    private final List<Map.Entry<String, String>> models = new ArrayList<>();
    private boolean initialized, editable, busy, draftEnabled, dirtyEnabled, serverHelp;
    private boolean draftSummary = true, dirtyReasoning;
    private boolean draftWebSearch = true, dirtyWebSearch;
    private boolean draftFastMode, dirtyFastMode;
    private String draftEffort = "default";
    private int ticks, modelOffset, accountIndex;
    private String status = "", authorizationUrl = "", callbackPort = "1455", sshTarget = "";
    private EditBox portInput, hostInput;

    public ChatGPTSubscriptionScreen(AIChatSettingsLLMSiteScreen parent) {
        super(Component.translatable("ai.touhou_little_maid.chat.site.chatgpt_subscription.name"));
        this.parent = parent;
    }

    private int left() { return width / 2 - 200; }
    private int top() { return height / 2 - 125; }
    private Component text(String key, Object... args) { return Component.translatable("gui.touhou_aifun.chatgpt." + key, args); }
    private boolean flag(String key) { return metadata.has(key) && metadata.get(key).getAsBoolean(); }

    @Override protected void init() {
        clearWidgets();
        int x = left() + 12, y = top();
        button(x + 250, y + 31, 126, draftEnabled ? "enabled" : "disabled", editable && !busy,
                () -> { draftEnabled = !draftEnabled; dirtyEnabled = true; init(); });
        if (!serverHelp) {
            button(x, y + 68, 154, flag("pending") ? "open_auth" : "login", editable && !busy,
                    () -> { if (flag("pending") && !authorizationUrl.isBlank()) openAuthorization(); else action(Action.LOGIN); });
            button(x + 160, y + 68, 112, "models", editable && !busy && flag("sharing"), () -> action(Action.MODELS));
            button(x + 278, y + 68, 98, flag("pending") ? "cancel" : "logout", editable && !busy && (flag("pending") || flag("connected")),
                    () -> { if (flag("pending")) action(Action.CANCEL); else confirmLogout(); });
            button(x, y + 94, 26, "previous", accounts.size() > 1 && !busy,
                    () -> { accountIndex = Math.floorMod(accountIndex - 1, accounts.size()); init(); });
            button(x + 204, y + 94, 26, "next", accounts.size() > 1 && !busy,
                    () -> { accountIndex = (accountIndex + 1) % accounts.size(); init(); });
            boolean different = !accounts.isEmpty() && !string(accounts.get(accountIndex), "client_id").equals(string(metadata, "client_id"));
            button(x + 236, y + 94, 66, "switch", editable && !busy && !flag("pending") && different, () -> action(Action.SELECT));
            button(x + 308, y + 94, 68, "add_account", editable && !busy && !flag("pending"), () -> action(Action.NEW_ACCOUNT));
            button(x, y + 119, 184, draftSummary ? "summary_on" : "summary_off", editable && !busy,
                    () -> { draftSummary = !draftSummary; dirtyReasoning = true; init(); }).setTooltip(Tooltip.create(text("summary_help")));
            button(x + 192, y + 119, 184, text("effort", text("effort." + draftEffort)), editable && !busy, () -> {
                draftEffort = ChatGPTReasoningSettings.EFFORTS.get((ChatGPTReasoningSettings.EFFORTS.indexOf(draftEffort) + 1)
                        % ChatGPTReasoningSettings.EFFORTS.size());
                dirtyReasoning = true; init();
            }).setTooltip(Tooltip.create(text("effort_help")));
            button(x + 106, y + 140, 106, draftWebSearch ? "search_on" : "search_off", editable && !busy,
                    () -> { draftWebSearch = !draftWebSearch; dirtyWebSearch = true; init(); })
                    .setTooltip(Tooltip.create(text("search_help")));
            button(x + 220, y + 140, 80, draftFastMode ? "fast_on" : "fast_off", editable && !busy,
                    () -> { draftFastMode = !draftFastMode; dirtyFastMode = true; init(); })
                    .setTooltip(Tooltip.create(text("fast_help").copy().append("\n").append(
                            text("fast_actual", text("tier." + serviceTier())))));
            button(x + 306, y + 140, 70, "manage_usage", true, () -> Util.getPlatform().openUri("https://chatgpt.com/settings/usage"));
        } else {
            button(x, y + 68, 184, "copy_ssh", true, () -> {
                if (sshTarget.isBlank() || sshTarget.strip().startsWith("-") || sshTarget.chars().anyMatch(c -> Character.isWhitespace(c) || "\"'`;|&$<>".indexOf(c) >= 0)) {
                    status = text("invalid_host").getString(); return;
                }
                int port = port(); if (port < 0) return;
                minecraft.keyboardHandler.setClipboard("ssh -N -L 127.0.0.1:" + port + ":127.0.0.1:" + port + " " + sshTarget);
                status = text("copied").getString();
            });
            button(x + 192, y + 68, 184, "reload", editable && !busy, () -> action(Action.RELOAD));
            portInput = new EditBox(font, x + 266, y + 140, 100, 20, text("port"));
            portInput.setMaxLength(5); portInput.setFilter(value -> value.matches("[0-9]*"));
            portInput.setValue(callbackPort); portInput.setResponder(value -> callbackPort = value); addRenderableWidget(portInput);
            hostInput = new EditBox(font, x + 92, y + 168, 274, 20, text("ssh_target"));
            hostInput.setMaxLength(128); hostInput.setValue(sshTarget); hostInput.setResponder(value -> sshTarget = value); addRenderableWidget(hostInput);
        }
        button(x, y + 224, 132, serverHelp ? "settings" : "server_help", true, () -> { serverHelp = !serverHelp; init(); });
        button(x + 180, y + 224, 92, "save", editable && !busy, () -> action(Action.SAVE));
        button(x + 280, y + 224, 96, "back", true, this::onClose);
        if (!initialized) { initialized = true; requestStatus(); }
    }

    private FlatColorButton button(int x, int y, int width, String key, boolean active, Runnable click) {
        return button(x, y, width, text(key), active, click);
    }

    private FlatColorButton button(int x, int y, int width, Component label, boolean active, Runnable click) {
        var button = new FlatColorButton(x, y, width, 20, label, b -> click.run());
        button.active = active; return addRenderableWidget(button);
    }

    private int port() {
        try { int value = Integer.parseInt(callbackPort); if (value >= 1024 && value <= 65535) return value; }
        catch (NumberFormatException ignored) { }
        status = text("invalid_port").getString(); return -1;
    }

    private void requestStatus() {
        if (minecraft != null && minecraft.player != null)
            AIFunNetwork.sendChatGPTAction(new AIFunChatGPTActionMessage(screenId, Action.STATUS, 1455, draftEnabled, "", draftSummary, draftEffort, draftWebSearch, draftFastMode));
    }

    private void action(Action action) {
        if (minecraft == null || minecraft.player == null) return;
        int port = port(); if (port < 0) return;
        String clientId = action == Action.SELECT && !accounts.isEmpty() ? string(accounts.get(accountIndex), "client_id") : "";
        busy = true; status = text(action == Action.MODELS ? "checking_models" : "working").getString();
        AIFunNetwork.sendChatGPTAction(new AIFunChatGPTActionMessage(screenId, action, port, draftEnabled, clientId, draftSummary, draftEffort, draftWebSearch, draftFastMode)); init();
    }

    private void confirmLogout() {
        minecraft.setScreen(new ConfirmScreen(yes -> { minecraft.setScreen(this); if (yes) action(Action.LOGOUT); },
                text("logout_confirm"), text("logout_detail")));
    }

    public void refreshFromServer(AIFunChatGPTStateMessage message) {
        if (!screenId.equals(message.screenId())) return;
        JsonObject incoming = JsonParser.parseString(message.metadata()).getAsJsonObject();
        boolean rebuild = !metadata.equals(incoming) || editable != message.editable() || busy != message.busy();
        metadata = incoming;
        editable = message.editable(); busy = message.busy(); status = message.status();
        if (!dirtyEnabled) draftEnabled = flag("enabled");
        if (!dirtyReasoning) {
            draftSummary = !metadata.has("reasoning_summary") || flag("reasoning_summary");
            draftEffort = ChatGPTReasoningSettings.normalize(string(metadata, "reasoning_effort"));
        }
        if (!dirtyWebSearch) draftWebSearch = !metadata.has("web_search") || flag("web_search");
        if (!dirtyFastMode) draftFastMode = flag("fast_mode");
        String selected = accounts.isEmpty() ? "" : string(accounts.get(accountIndex), "client_id");
        accounts.clear(); accountIndex = 0;
        if (metadata.has("accounts")) metadata.getAsJsonArray("accounts").forEach(value -> accounts.add(value.getAsJsonObject()));
        for (int i = 0; i < accounts.size(); i++) if (string(accounts.get(i), "client_id").equals(selected)) accountIndex = i;
        models.clear();
        if (metadata.has("models")) metadata.getAsJsonObject("models").entrySet().forEach(entry -> models.add(Map.entry(entry.getKey(), entry.getValue().getAsString())));
        modelOffset = Math.min(modelOffset, Math.max(0, models.size() - VISIBLE_MODELS));
        if (!flag("pending")) authorizationUrl = "";
        boolean open = isAuthorizationUrl(message.authorizationUrl());
        if (open) authorizationUrl = message.authorizationUrl();
        if (rebuild) {
            boolean focusPort = getFocused() == portInput && portInput != null;
            boolean focusHost = getFocused() == hostInput && hostInput != null;
            init();
            if (serverHelp && focusPort) setFocused(portInput);
            if (serverHelp && focusHost) setFocused(hostInput);
        }
        if (message.saved()) { dirtyEnabled = false; dirtyReasoning = false; dirtyWebSearch = false; dirtyFastMode = false; onClose(); }
        else if (open) openAuthorization();
    }

    static boolean isAuthorizationUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "auth.openai.com".equals(uri.getHost())
                    && "/api/accounts/authorize".equals(uri.getPath()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException e) { return false; }
    }

    private void openAuthorization() { if (isAuthorizationUrl(authorizationUrl)) Util.getPlatform().openUri(authorizationUrl); }

    private String serviceTier() {
        String value = string(metadata, "last_service_tier");
        return java.util.Set.of("fast", "priority", "default", "flex", "ultrafast").contains(value) ? value : "unknown";
    }

    @Override public void tick() {
        super.tick();
        if (serverHelp) { if (portInput != null) portInput.tick(); if (hostInput != null) hostInput.tick(); }
        if (++ticks % 40 == 0) requestStatus();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!serverHelp && mouseX >= left() + 12 && mouseX < left() + 388 && mouseY >= top() + 162 && mouseY < top() + 203) {
            modelOffset = Math.max(0, Math.min(Math.max(0, models.size() - VISIBLE_MODELS), modelOffset - (int) Math.signum(delta))); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int x = left() + 12, y = top();
        graphics.fill(left(), y, left() + 400, y + 250, 0xef172430);
        graphics.fill(left(), y, left() + 400, y + 24, 0xff26394b);
        graphics.drawCenteredString(font, title, width / 2, y + 8, 0xffffff);
        String email = string(metadata, "email");
        graphics.drawString(font, font.plainSubstrByWidth(text("account", email.isBlank() ? text("none").getString() : email).getString(), 240), x, y + 33, 0xffffff);
        String state = flag("pending") ? "waiting" : flag("sharing") ? "connected" : flag("connected") ? "identity_only" : "signed_out";
        graphics.drawString(font, text(state), x, y + 49, flag("sharing") ? 0x8fdfb0 : 0xd8c39e);
        if (!serverHelp) {
            String selected = accounts.isEmpty() ? text("no_accounts").getString() : string(accounts.get(accountIndex), "email");
            graphics.drawString(font, font.plainSubstrByWidth(selected, 166), x + 32, y + 100, 0xdddddd);
            graphics.drawString(font, font.plainSubstrByWidth(text("model_count", models.size()).getString(), 100), x, y + 147, 0xffffff);
            graphics.fill(x, y + 162, x + 376, y + 203, 0xff101a23);
            if (models.isEmpty()) graphics.drawCenteredString(font, text("empty_models"), width / 2, y + 178, 0x9babb9);
            for (int i = 0; i < VISIBLE_MODELS && modelOffset + i < models.size(); i++) {
                var model = models.get(modelOffset + i);
                JsonObject checks = metadata.has("model_checks") ? metadata.getAsJsonObject("model_checks") : new JsonObject();
                boolean verified = checks.has(model.getKey()) && checks.getAsJsonObject(model.getKey()).get("usable").getAsBoolean();
                String label = model.getValue() + (verified ? text("verified").getString() : "");
                graphics.drawString(font, font.plainSubstrByWidth(label, 364), x + 6, y + 164 + i * 20, verified ? 0x8fdfb0 : 0xffffff);
                graphics.drawString(font, font.plainSubstrByWidth(model.getKey(), 364), x + 6, y + 174 + i * 20, 0x9babb9);
            }
        } else {
            graphics.drawWordWrap(font, text("remote_help"), x, y + 97, 370, 0xdddddd);
            graphics.drawString(font, text("port"), x, y + 145, 0xffffff);
            graphics.drawString(font, text("ssh_target"), x, y + 173, 0xffffff);
            graphics.drawString(font, text("local_help"), x, y + 192, 0x9babb9);
        }
        String note = status.isBlank() ? text("owner").getString() : status;
        graphics.drawString(font, font.plainSubstrByWidth(note, 376), x, y + 207, 0xbacbd8);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= x && mouseX < x + 376 && mouseY >= y + 205 && mouseY < y + 219)
            graphics.renderTooltip(font, font.split(Component.literal(note), 360), mouseX, mouseY);
        else if (!serverHelp && mouseX >= x && mouseX < x + 100 && mouseY >= y + 143 && mouseY < y + 160) {
            StringBuilder details = new StringBuilder(text("discovery_help").getString());
            if (metadata.has("model_checks")) metadata.getAsJsonObject("model_checks").entrySet().forEach(entry ->
                    details.append('\n').append(entry.getKey()).append(": ").append(string(entry.getValue().getAsJsonObject(), "detail")));
            graphics.renderTooltip(font, font.split(Component.literal(details.toString()), 360), mouseX, mouseY);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
            if (minecraft.player != null) OpenAIConfigMessage.sendToServer();
        }
    }
    public void onSitesSynced(SyncAISitesMessage message) { if (parent != null) parent.reopenSelf(message.llmSites(), message.ttsSites()); }
}
