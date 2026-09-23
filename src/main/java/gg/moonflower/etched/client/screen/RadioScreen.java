package gg.moonflower.etched.client.screen;

import gg.moonflower.etched.common.blockentity.RadioBlockEntity;
import gg.moonflower.etched.client.radio.RadioClientRuntime;
import gg.moonflower.etched.common.menu.RadioMenu;
import gg.moonflower.etched.common.network.EtchedMessages;
import gg.moonflower.etched.common.network.play.ServerboundSetRadioUrlPacket;
import gg.moonflower.etched.common.radio.RadioClientBridge;
import gg.moonflower.etched.common.radio.RadioUrlValidator;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * @author Ocelot
 */
public class RadioScreen extends AbstractContainerScreen<RadioMenu> {

    private static final ResourceLocation BACKGROUND_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "textures/gui/radio_background.png");
    private static final ResourceLocation URL_ACTIVE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "textures/gui/radio_url_active.png");
    private static final ResourceLocation URL_INACTIVE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Etched.MOD_ID, "textures/gui/radio_url_inactive.png");
    private static final Component LOADING_URL = Component.translatable("screen." + Etched.MOD_ID + ".radio.loading_url");
    private static final Component INVALID_URL = Component.translatable("screen." + Etched.MOD_ID + ".radio.error.invalid_url");
    private static final Component PLAY = Component.translatable("screen." + Etched.MOD_ID + ".radio.play");
    private static final Component STOP = Component.translatable("screen." + Etched.MOD_ID + ".radio.stop");
    private static final Component CLOSE = Component.translatable("screen." + Etched.MOD_ID + ".radio.close");
    private static final Component HISTORY = Component.translatable("screen." + Etched.MOD_ID + ".radio.history");
    private static final Component CLEAR_HISTORY = Component.translatable("screen." + Etched.MOD_ID + ".radio.history.clear");
    private static final int BACKGROUND_WIDTH = 256;
    private static final int BACKGROUND_HEIGHT = 180;
    private static final int URL_BACKGROUND_WIDTH = 240;
    private static final int URL_BACKGROUND_HEIGHT = 14;
    private static final int URL_FIELD_WIDTH = 234;
    private static final int BUTTON_WIDTH = 72;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTONS_X = 14;
    private static final int HISTORY_X = 8;
    private static final int HISTORY_Y = 72;
    private static final int HISTORY_WIDTH = 240;
    private static final int HISTORY_HEIGHT = 74;
    private static final int TOOLTIP_URL_LIMIT = 512;
    private static final int TITLE_COLOR = 0xD8A066;

    private final RadioEditState editState = new RadioEditState();
    private final RadioClientRuntime radioRuntime = RadioClientRuntime.getInstance();
    private final ResourceKey<Level> radioDimension;
    private final BlockPos radioPos;
    private EditBox url;
    private RadioHistoryList historyList;
    private Button playButton;
    private Button stopButton;
    private Button clearHistoryButton;

    public RadioScreen(RadioMenu menu, Inventory inventory, Component component) {
        super(menu, inventory, component);
        this.imageWidth = BACKGROUND_WIDTH;
        this.imageHeight = BACKGROUND_HEIGHT;
        this.radioDimension = inventory.player.level().dimension();
        this.radioPos = RadioClientBridge.consumeOpenedMenu(inventory.player.level()).orElse(null);
    }

    @Override
    protected void init() {
        String selectedHistory = this.historyList == null ? null : this.historyList.selectedStation();
        double historyScroll = this.historyList == null ? 0.0D : this.historyList.getScrollAmount();
        boolean historyFocused = this.historyList != null && this.getFocused() == this.historyList;
        super.init();
        this.url = new EditBox(this.font, this.leftPos + 10, this.topPos + 21, URL_FIELD_WIDTH, 16,
                Component.translatable("container." + Etched.MOD_ID + ".radio.url"));
        this.url.setTextColor(-1);
        this.url.setTextColorUneditable(-1);
        this.url.setBordered(false);
        this.url.setMaxLength(RadioUrlValidator.MAX_LENGTH);
        this.url.setVisible(this.editState.loaded());
        this.url.setEditable(this.editState.loaded());
        this.url.setCanLoseFocus(true);
        this.url.setValue(this.editState.value());
        this.url.setResponder(value -> {
            this.editState.update(value);
            this.updateActionButtons();
        });
        this.addRenderableWidget(this.url);
        List<String> history = this.radioRuntime.currentEntries();
        this.historyList = new RadioHistoryList(this.minecraft, this.font,
                this.leftPos + HISTORY_X, this.topPos + HISTORY_Y, HISTORY_WIDTH, HISTORY_HEIGHT,
                history, this.url::setValue);
        this.historyList.setActive(this.editState.loaded());
        this.historyList.restoreView(selectedHistory, historyScroll);
        this.addRenderableWidget(this.historyList);
        if (historyFocused) {
            this.setFocused(this.historyList);
        }
        this.clearHistoryButton = this.addRenderableWidget(Button.builder(CLEAR_HISTORY, button -> {
                    if (this.radioRuntime.clearCurrentHistory()) {
                        this.refreshHistory();
                    }
                })
                .bounds(this.leftPos + 188, this.topPos + 52, 60, 14)
                .build());
        int buttonY = this.topPos + 152;
        this.playButton = this.addRenderableWidget(Button.builder(PLAY, button ->
                        this.editState.play().ifPresent(this::sendUrl))
                .bounds(this.leftPos + BUTTONS_X, buttonY, BUTTON_WIDTH, 20)
                .build());
        this.stopButton = this.addRenderableWidget(Button.builder(STOP, button -> {
                    if (this.editState.stop()) {
                        this.sendUrl("");
                    }
                })
                .bounds(this.leftPos + BUTTONS_X + BUTTON_WIDTH + BUTTON_GAP, buttonY, BUTTON_WIDTH, 20)
                .build());
        this.addRenderableWidget(Button.builder(CLOSE, button -> this.onClose())
                .bounds(this.leftPos + BUTTONS_X + (BUTTON_WIDTH + BUTTON_GAP) * 2,
                        buttonY, BUTTON_WIDTH, 20)
                .build());
        this.updateActionButtons();
    }

    @Override
    public void containerTick() {
        this.url.tick();
        this.syncPlaybackState();
        this.refreshHistory();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float f, int mouseX, int mouseY) {
        guiGraphics.blit(BACKGROUND_TEXTURE, this.leftPos, this.topPos, 0, 0,
                BACKGROUND_WIDTH, BACKGROUND_HEIGHT, BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
        ResourceLocation urlTexture = this.editState.loaded() ? URL_ACTIVE_TEXTURE : URL_INACTIVE_TEXTURE;
        guiGraphics.blit(urlTexture, this.leftPos + 8, this.topPos + 18, 0, 0,
                URL_BACKGROUND_WIDTH, URL_BACKGROUND_HEIGHT, URL_BACKGROUND_WIDTH, URL_BACKGROUND_HEIGHT);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE_COLOR, false);
        guiGraphics.drawString(this.font, HISTORY, HISTORY_X, 59, TITLE_COLOR, false);
        if (!this.editState.loaded()) {
            guiGraphics.drawString(this.font, LOADING_URL, 8, 41, 8421504, false);
        } else if (!this.editState.valid()) {
            guiGraphics.drawString(this.font, INVALID_URL, 8, 41, 0xFF5555, false);
        }
    }

    @Override
    public boolean keyPressed(int i, int j, int k) {
        return this.url.isFocused() && this.url.keyPressed(i, j, k)
                || (this.url.isFocused() && this.url.isVisible() && i != 256 && i != 258)
                || super.keyPressed(i, j, k);
    }

    public void receiveUrl(String url) {
        String fallback = "";
        Minecraft minecraft = Minecraft.getInstance();
        if (this.radioPos != null && minecraft.level != null
                && minecraft.level.getBlockEntity(this.radioPos) instanceof RadioBlockEntity radio
                && radio.getUrl() != null) {
            fallback = radio.getUrl();
        }
        if (!this.editState.receiveInitialUrl(url, fallback)) {
            return;
        }
        this.url.setVisible(true);
        this.url.setEditable(true);
        this.url.setValue(this.editState.value());
        this.setFocused(this.url);
        this.url.setFocused(true);
        this.historyList.setActive(true);
        this.updateActionButtons();
    }

    private void sendUrl(String value) {
        if (this.radioPos != null) {
            if (value.isEmpty()) {
                this.radioRuntime.cancelExpectedStation(this.radioDimension, this.radioPos);
            } else {
                this.radioRuntime.expectStation(this.radioDimension, this.radioPos, value);
            }
        }
        EtchedMessages.PLAY.sendToServer(new ServerboundSetRadioUrlPacket(this.menu.containerId, value));
        this.updateActionButtons();
    }

    private void updateActionButtons() {
        if (this.playButton != null) {
            this.playButton.active = this.editState.canPlay();
        }
        if (this.stopButton != null) {
            this.stopButton.active = this.editState.canStop();
        }
        if (this.clearHistoryButton != null) {
            this.clearHistoryButton.active = this.editState.loaded() && !this.radioRuntime.currentEntries().isEmpty();
        }
    }

    private void syncPlaybackState() {
        if (this.radioPos == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.level.getBlockEntity(this.radioPos) instanceof RadioBlockEntity radio) {
            this.editState.receivePlaybackState(radio.isManuallyEnabled());
            this.updateActionButtons();
        }
    }

    private void refreshHistory() {
        if (this.historyList == null) {
            return;
        }
        List<String> entries = this.radioRuntime.currentEntries();
        if (!this.historyList.stations().equals(entries)) {
            this.historyList.replaceStations(entries);
        }
        this.historyList.setActive(this.editState.loaded());
        if (this.clearHistoryButton != null) {
            this.clearHistoryButton.active = this.editState.loaded() && !entries.isEmpty();
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (this.historyList == null) {
            return;
        }
        this.historyList.tooltipAt(mouseX, mouseY).ifPresent(value -> {
            String tooltip = value.length() > TOOLTIP_URL_LIMIT
                    ? value.substring(0, TOOLTIP_URL_LIMIT) + "..." : value;
            guiGraphics.renderTooltip(this.font, this.font.split(Component.literal(tooltip), 240), mouseX, mouseY);
        });
    }
}
