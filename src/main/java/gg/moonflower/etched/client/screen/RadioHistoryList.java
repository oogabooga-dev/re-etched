package gg.moonflower.etched.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Scrollable recent-station list embedded in the radio screen. */
final class RadioHistoryList extends ObjectSelectionList<RadioHistoryList.Entry> {

    private static final Component EMPTY = Component.translatable("screen.etched.radio.history.empty");
    private static final int ROW_HEIGHT = 14;
    private static final int MAX_NARRATED_URL_LENGTH = 512;

    private final Font font;
    private final Consumer<String> stationSelected;
    private boolean active;

    RadioHistoryList(Minecraft minecraft, Font font, int x, int y, int width, int height,
                     List<String> stations, Consumer<String> stationSelected) {
        super(minecraft, width, height, y, y + height, ROW_HEIGHT);
        this.font = font;
        this.stationSelected = stationSelected;
        this.setLeftPos(x);
        this.setRenderBackground(false);
        this.setRenderTopAndBottom(false);
        this.replaceStations(stations);
    }

    void replaceStations(List<String> stations) {
        String selectedUrl = this.getSelected() == null ? null : this.getSelected().url;
        double scroll = this.getScrollAmount();
        this.setFocused((GuiEventListener) null);
        this.clearEntries();
        for (String station : stations) {
            Entry entry = new Entry(station);
            this.addEntry(entry);
            if (station.equals(selectedUrl)) {
                this.setSelected(entry);
            }
        }
        this.setScrollAmount(scroll);
    }

    List<String> stations() {
        return this.children().stream().map(entry -> entry.url).toList();
    }

    String selectedStation() {
        return this.getSelected() == null ? null : this.getSelected().url;
    }

    void restoreView(String selectedStation, double scrollAmount) {
        if (selectedStation != null) {
            this.children().stream()
                    .filter(entry -> entry.url.equals(selectedStation))
                    .findFirst()
                    .ifPresent(this::setSelected);
        }
        this.setScrollAmount(scrollAmount);
    }

    void setActive(boolean active) {
        this.active = active;
    }

    Optional<String> tooltipAt(double mouseX, double mouseY) {
        Entry entry = this.getEntryAtPosition(mouseX, mouseY);
        return entry != null && this.font.width(entry.url) > this.textWidth()
                ? Optional.of(entry.url) : Optional.empty();
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    public int getRowLeft() {
        return this.getLeft() + 4;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.getRight() - 6;
    }

    @Override
    protected void renderSelection(GuiGraphics guiGraphics, int top, int width, int height, int outerColor, int innerColor) {
        int left = this.selectionLeft();
        int right = this.selectionRight();
        guiGraphics.fill(left, top - 2, right, top + height + 2, outerColor);
        guiGraphics.fill(left + 1, top - 1, right - 1, top + height + 1, innerColor);
    }

    private int selectionLeft() {
        return this.getRowLeft() - 2;
    }

    private int selectionRight() {
        return this.getScrollbarPosition();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.active && super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return this.active && super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    protected void renderDecorations(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.getItemCount() == 0) {
            guiGraphics.drawCenteredString(this.font, EMPTY,
                    (this.getLeft() + this.getRight()) / 2, this.getTop() + (this.getHeight() - 8) / 2,
                    this.active ? 0xA0A0A0 : 0x707070);
        }
    }

    private int textWidth() {
        return this.getRowWidth() - 6;
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> {

        private final String url;

        private Entry(String url) {
            this.url = url;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            if (hovered && RadioHistoryList.this.active) {
                guiGraphics.fill(RadioHistoryList.this.selectionLeft() + 1, top - 1,
                        RadioHistoryList.this.selectionRight() - 1, top + height + 1, 0x40FFFFFF);
            }
            int maximumWidth = RadioHistoryList.this.textWidth();
            String text = this.url;
            if (RadioHistoryList.this.font.width(text) > maximumWidth) {
                int suffixWidth = RadioHistoryList.this.font.width("...");
                text = RadioHistoryList.this.font.plainSubstrByWidth(text, maximumWidth - suffixWidth) + "...";
            }
            guiGraphics.drawString(RadioHistoryList.this.font, text, left, top + 3,
                    RadioHistoryList.this.active ? 0xFFFFFF : 0x707070, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !RadioHistoryList.this.active) {
                return false;
            }
            RadioHistoryList.this.setSelected(this);
            RadioHistoryList.this.stationSelected.accept(this.url);
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!RadioHistoryList.this.active
                    || keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER
                    && keyCode != GLFW.GLFW_KEY_SPACE) {
                return false;
            }
            RadioHistoryList.this.setSelected(this);
            RadioHistoryList.this.stationSelected.accept(this.url);
            return true;
        }

        @Override
        public Component getNarration() {
            String narratedUrl = this.url.length() > MAX_NARRATED_URL_LENGTH
                    ? this.url.substring(0, MAX_NARRATED_URL_LENGTH) + "..." : this.url;
            return Component.translatable("screen.etched.radio.history.entry", narratedUrl);
        }
    }
}
