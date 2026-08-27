package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.ClientAvailableSitesSync;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.SupportLanguage;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.maid.management.MaidManagementEntry;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MaidManagementScreen extends Screen {
    private static final int BASE_WIDTH = 540;
    private static final int BASE_HEIGHT = 310;
    private static final int LIST_WIDTH = 205;
    private static final int ROW_HEIGHT = 38;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final @Nullable Screen parent;
    private List<MaidManagementEntry> entries;
    private UUID selectedId;
    private int scrollOffset;
    private int startX;
    private int startY;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private long statusTimestamp = -1;
    private Component statusMessage = Component.empty();

    public MaidManagementScreen(@Nullable Screen parent, List<MaidManagementEntry> entries,
                                String operationStatus) {
        super(Component.translatable("gui.touhou_aifun.maid_management"));
        this.parent = parent;
        this.entries = new ArrayList<>(entries);
        this.selectedId = entries.isEmpty() ? null : entries.get(0).maidId();
        setStatus(operationStatus);
    }

    @Override
    protected void init() {
        clearWidgets();
        panelWidth = Math.max(360, Math.min(BASE_WIDTH, width - 20));
        panelHeight = Math.max(220, Math.min(BASE_HEIGHT, height - 20));
        startX = (width - panelWidth) / 2;
        startY = (height - panelHeight) / 2;
        listTop = startY + 31;
        listBottom = startY + panelHeight - 31;
        clampScroll();

        int rightX = startX + LIST_WIDTH + 12;
        int rightWidth = panelWidth - LIST_WIDTH - 20;
        int footerY = startY + panelHeight - 25;

        addRenderableWidget(new FlatColorButton(startX + 5, footerY, 72, 20,
                Component.translatable("gui.touhou_aifun.maid_management.refresh"),
                button -> AIFunNetwork.requestMaidList(false)));

        MaidManagementEntry selected = selected();
        FlatColorButton copyAll = new FlatColorButton(startX + panelWidth - 115, startY + 5, 110, 20,
                Component.translatable("gui.touhou_aifun.maid_management.copy_all"),
                button -> confirmCopyToAll());
        copyAll.active = selected != null && selected.configKnown() && entries.size() > 1;
        addRenderableWidget(copyAll);
        FlatColorButton edit = new FlatColorButton(rightX, footerY, Math.max(70, (rightWidth - 166) / 2), 20,
                Component.translatable("gui.touhou_aifun.maid_management.edit"), button -> openEditor());
        edit.active = selected != null;
        addRenderableWidget(edit);

        int recallX = rightX + edit.getWidth() + 4;
        FlatColorButton recall = new FlatColorButton(recallX, footerY,
                Math.max(70, rightWidth - edit.getWidth() - 86), 20,
                Component.translatable("gui.touhou_aifun.maid_management.recall"), button -> recallSelected());
        recall.active = selected != null;
        addRenderableWidget(recall);

        addRenderableWidget(new FlatColorButton(startX + panelWidth - 78, footerY, 73, 20,
                CommonComponents.GUI_DONE, button -> onClose()));
    }

    private void openEditor() {
        MaidManagementEntry selected = selected();
        if (selected != null && minecraft != null) {
            minecraft.setScreen(new MaidAIConfigScreen(this, selected));
        }
    }

    private void recallSelected() {
        MaidManagementEntry selected = selected();
        if (selected != null) {
            AIFunNetwork.recallManagedMaid(selected.maidId());
        }
    }

    private void confirmCopyToAll() {
        MaidManagementEntry selected = selected();
        if (selected == null || minecraft == null) {
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (minecraft != null) {
                minecraft.setScreen(this);
            }
            if (confirmed) {
                AIFunNetwork.copyManagedMaidConfigToAll(selected.maidId());
            }
        }, Component.translatable("gui.touhou_aifun.maid_management.copy_all.confirm_title"),
                Component.translatable("gui.touhou_aifun.maid_management.copy_all.confirm", selected.name())));
    }

    public void refreshFromServer(List<MaidManagementEntry> nextEntries, String operationStatus) {
        UUID previous = selectedId;
        entries = new ArrayList<>(nextEntries);
        selectedId = entries.stream().anyMatch(entry -> entry.maidId().equals(previous))
                ? previous : entries.isEmpty() ? null : entries.get(0).maidId();
        setStatus(operationStatus);
        if (minecraft != null) {
            minecraft.execute(this::init);
        }
    }

    private void setStatus(String operationStatus) {
        if (operationStatus != null && !operationStatus.isBlank()) {
            statusMessage = Component.translatable(
                    "gui.touhou_aifun.maid_management.status." + operationStatus);
            statusTimestamp = System.currentTimeMillis();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xE0121212);
        graphics.fill(startX + 4, listTop - 4, startX + LIST_WIDTH, listBottom + 2, 0xB0202020);
        graphics.fill(startX + LIST_WIDTH + 6, listTop - 4, startX + panelWidth - 4, listBottom + 2,
                0xA0181818);
        graphics.drawCenteredString(font, title, width / 2, startY + 9, 0xFFF3EFE0);

        renderRows(graphics, mouseX, mouseY);
        renderDetails(graphics);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.touhou_aifun.maid_management.empty"),
                    startX + LIST_WIDTH / 2, listTop + 18, 0xFF999999);
        }
        if (System.currentTimeMillis() - statusTimestamp < 4000) {
            graphics.drawCenteredString(font, statusMessage, width / 2, startY + panelHeight - 38,
                    statusIsError() ? 0xFFFF5555 : 0xFF55CC77);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int visible = visibleRows();
        int rowWidth = LIST_WIDTH - 10;
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            int index = scrollOffset + visibleIndex;
            if (index >= entries.size()) {
                break;
            }
            MaidManagementEntry entry = entries.get(index);
            int x = startX + 5;
            int y = listTop + visibleIndex * ROW_HEIGHT;
            boolean selected = entry.maidId().equals(selectedId);
            boolean hovered = mouseX >= x && mouseX < x + rowWidth && mouseY >= y
                    && mouseY < y + ROW_HEIGHT - 2;
            graphics.fill(x, y, x + rowWidth, y + ROW_HEIGHT - 2,
                    selected ? 0xD034678A : hovered ? 0xC03A3A3A : 0xA02A2A2A);
            graphics.fill(x, y, x + 3, y + ROW_HEIGHT - 2, stateColor(entry.state()));

            String name = font.plainSubstrByWidth(entry.name().getString(), rowWidth - 54);
            graphics.drawString(font, name, x + 8, y + 5, 0xFFF3EFE0, false);
            graphics.drawString(font, stateLabel(entry.state()), x + rowWidth - 43, y + 5,
                    stateColor(entry.state()), false);

            String location = shortDimension(entry.dimension()) + " · " + entry.position().toShortString();
            location = font.plainSubstrByWidth(location, rowWidth - 14);
            graphics.drawString(font, location, x + 8, y + 20, 0xFFAAAAAA, false);
        }
        if (entries.size() > visible) {
            int trackX = startX + LIST_WIDTH - 4;
            int trackHeight = listBottom - listTop;
            int thumbHeight = Math.max(14, trackHeight * visible / entries.size());
            int maxOffset = Math.max(1, entries.size() - visible);
            int thumbY = listTop + (trackHeight - thumbHeight) * scrollOffset / maxOffset;
            graphics.fill(trackX, listTop, trackX + 2, listBottom, 0xFF333333);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }

    private void renderDetails(GuiGraphics graphics) {
        MaidManagementEntry entry = selected();
        if (entry == null) {
            return;
        }
        int x = startX + LIST_WIDTH + 14;
        int maxWidth = panelWidth - LIST_WIDTH - 24;
        int y = listTop;
        graphics.drawString(font, font.plainSubstrByWidth(entry.name().getString(), maxWidth), x, y,
                0xFFFFFFFF, false);
        y += 16;
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.state",
                stateLabel(entry.state()));
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.dimension",
                Component.literal(entry.dimension()));
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.position",
                Component.literal(entry.position().toShortString()));
        if (entry.state() == MaidManagementEntry.State.UNLOADED) {
            y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.last_seen",
                    Component.literal(TIME_FORMAT.format(Instant.ofEpochMilli(entry.lastSeen()))));
        } else {
            y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.health",
                    Component.literal("%.1f / %.1f".formatted(entry.health(), entry.maxHealth())));
        }
        if (!entry.modelId().isBlank()) {
            y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.model",
                    Component.literal(entry.modelId()));
        }
        if (!entry.taskId().isBlank()) {
            y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.task",
                    Component.literal(entry.taskId()));
        }
        y += 4;
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.llm",
                Component.literal(llmSummary(entry)));
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_management.tts",
                Component.literal(ttsSummary(entry)));
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_config.public_maid",
                onOff(entry.publicMaid()));
        y = detailLine(graphics, x, y, maxWidth, "gui.touhou_aifun.maid_config.friendly_fire",
                onOff(entry.friendlyFireAllowed()));
        if (entry.configPending()) {
            graphics.drawWordWrap(font,
                    Component.translatable("gui.touhou_aifun.maid_management.config_pending"),
                    x, y + 3, maxWidth, 0xFFFFCC55);
        } else if (!entry.configKnown()) {
            graphics.drawWordWrap(font,
                    Component.translatable("gui.touhou_aifun.maid_management.config_unknown"),
                    x, y + 3, maxWidth, 0xFFFFAA55);
        }
    }

    private int detailLine(GuiGraphics graphics, int x, int y, int maxWidth, String labelKey,
                           Component value) {
        String line = Component.translatable(labelKey).getString() + ": " + value.getString();
        graphics.drawString(font, font.plainSubstrByWidth(line, maxWidth), x, y, 0xFFBBBBBB, false);
        return y + 13;
    }

    private String llmSummary(MaidManagementEntry entry) {
        String site = entry.config().llmSite().isBlank() ? "*" : entry.config().llmSite();
        String model = ClientAvailableSitesSync.getLLMModelName(entry.config().llmSite(),
                entry.config().llmModel());
        return site + " / " + model;
    }

    private String ttsSummary(MaidManagementEntry entry) {
        if (MaidAIChatSerializable.isNoTTSSite(entry.config().ttsSite())) {
            return Component.translatable("ai.touhou_little_maid.chat.site.none.name").getString();
        }
        String site = entry.config().ttsSite().isBlank() ? "*" : entry.config().ttsSite();
        String model = ClientAvailableSitesSync.getTTSModelName(entry.config().ttsSite(),
                entry.config().ttsModel());
        String language = SupportLanguage.getLanguageName(entry.config().ttsLanguage()).getString();
        return site + " / " + model + " · " + language;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= startX + 5 && mouseX < startX + LIST_WIDTH - 5
                && mouseY >= listTop && mouseY < listBottom) {
            int index = scrollOffset + (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < entries.size()) {
                selectedId = entries.get(index).maidId();
                init();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= startX && mouseX <= startX + LIST_WIDTH && mouseY >= listTop && mouseY <= listBottom) {
            scrollOffset -= (int) Math.signum(delta);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - visibleRows())));
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private MaidManagementEntry selected() {
        if (selectedId == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.maidId().equals(selectedId)).findFirst().orElse(null);
    }

    private static int stateColor(MaidManagementEntry.State state) {
        return switch (state) {
            case LOADED_HERE -> 0xFF55DD77;
            case LOADED_OTHER_DIMENSION -> 0xFF55AAFF;
            case UNLOADED -> 0xFF999999;
        };
    }

    private static Component stateLabel(MaidManagementEntry.State state) {
        return Component.translatable("gui.touhou_aifun.maid_management.state."
                + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    private static String shortDimension(String dimension) {
        int separator = dimension.indexOf(':');
        return separator >= 0 ? dimension.substring(separator + 1) : dimension;
    }

    private boolean statusIsError() {
        String text = statusMessage.getString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("无法") || text.contains("失败") || text.contains("不能")
                || text.contains("not ") || text.contains("cannot") || text.contains("unloaded")
                || text.contains("cooldown");
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
