package autismclient.gui.screen;

import autismclient.gui.packui.PackUiDropdown;
import autismclient.gui.packui.PackUiOverlayButton;
import autismclient.gui.packui.PackUiText;
import autismclient.gui.packui.PackUiTheme;
import autismclient.gui.packui.PackUiTone;
import autismclient.util.PackUtilProxyManager;
import autismclient.util.PackUtilUiScale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class PackUtilProxyConfigScreen extends Screen {
    private static final PackUiTheme THEME = new PackUiTheme();
    private static final int BG = 0xFF0E0E10;
    private static final int PANEL_BG = 0xE818181B;
    private static final int PANEL_BG_SOFT = 0xB8141417;
    private static final int BORDER = 0xFF332428;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int SUCCESS = 0xFF35D873;
    private static final int WARN = 0xFFFFC857;
    private static final int PANEL_WIDTH = 560;
    private static final int ROW_HEIGHT = 42;
    private static final int[] TIMEOUT_VALUES = {1000, 2000, 3000, 5000, 7500, 10000, 15000};
    private static final int[] THREAD_VALUES = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] RETRY_VALUES = {0, 1, 2, 3, 5};
    private static final int[] PRUNE_LATENCY_VALUES = {0, 500, 1000, 1500, 2000, 3000, 5000, 10000};
    private static final int[] PRUNE_COUNT_VALUES = {0, 25, 50, 100, 250, 500, 1000};

    private final Screen parent;
    private final List<PackUiOverlayButton> buttons = new ArrayList<>();
    private final List<PackUiDropdown> dropdowns = new ArrayList<>();
    private final List<ConfigRow> rows = new ArrayList<>();

    public PackUtilProxyConfigScreen(Screen parent) {
        super(Component.literal("Proxy Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildRows();
    }

    private void rebuildRows() {
        buttons.clear();
        dropdowns.clear();
        rows.clear();
        int panelX = panelX();
        int y = 70;
        PackUtilProxyManager mgr = PackUtilProxyManager.get();

        addDropdownRow("Timeout", "How long each proxy check can take.", mgr.getTimeoutMs(), mgr::setTimeoutMs, TIMEOUT_VALUES, this::formatMs, panelX, y);
        y += ROW_HEIGHT;
        addDropdownRow("Threads", "How many proxies can be checked at once.", mgr.getThreads(), mgr::setThreads, THREAD_VALUES, Integer::toString, panelX, y);
        y += ROW_HEIGHT;
        addDropdownRow("Retries", "Extra attempts when a proxy check times out.", mgr.getRetries(), mgr::setRetries, RETRY_VALUES, Integer::toString, panelX, y);
        y += ROW_HEIGHT;
        addDropdownRow("Prune latency", "Cleanup removes alive proxies slower than this.", mgr.getPruneLatency(), mgr::setPruneLatency, PRUNE_LATENCY_VALUES, value -> value <= 0 ? "Off" : formatMs(value), panelX, y);
        y += ROW_HEIGHT;
        addDropdownRow("Prune limit", "Cleanup keeps only the fastest proxies when enabled.", mgr.getPruneToCount(), mgr::setPruneToCount, PRUNE_COUNT_VALUES, value -> value <= 0 ? "No limit" : Integer.toString(value), panelX, y);
        y += ROW_HEIGHT;
        addToggleRow("Sort by latency", mgr.isSortByLatency(), "Cleanup sorts alive proxies from fastest to slowest.", value -> mgr.setSortByLatency(value), panelX, y);
        y += ROW_HEIGHT;
        addToggleRow("Prune dead", mgr.isPruneDead(), "Cleanup removes proxies marked as dead.", value -> mgr.setPruneDead(value), panelX, y);

        buttons.add(PackUiOverlayButton.create(panelX + 12, panelBottom() - 36, 104, 22, Component.literal("Back"), b -> this.minecraft.setScreen(parent)).setVariant(PackUiOverlayButton.Variant.SECONDARY));
        buttons.add(PackUiOverlayButton.create(panelX + PANEL_WIDTH - 126, panelBottom() - 36, 112, 22, Component.literal("Defaults"), b -> resetDefaults()).setVariant(PackUiOverlayButton.Variant.SECONDARY));
    }

    private void addDropdownRow(String label, String hint, int current, IntConsumer setter, int[] options, ValueFormatter formatter, int panelX, int y) {
        rows.add(new ConfigRow(label, "", hint, y, false, false));
        dropdowns.add(new PackUiDropdown(
            panelX + PANEL_WIDTH - 132,
            y + 10,
            118,
            20,
            optionLabels(options, formatter),
            selectedOptionIndex(current, options),
            index -> {
                if (index >= 0 && index < options.length) {
                    setter.accept(options[index]);
                    rebuildRows();
                }
            }
        ));
    }

    private void addToggleRow(String label, boolean enabled, String hint, BooleanConsumer setter, int panelX, int y) {
        rows.add(new ConfigRow(label, enabled ? "Enabled" : "Disabled", hint, y, true, enabled));
        buttons.add(PackUiOverlayButton.create(panelX + PANEL_WIDTH - 132, y + 10, 118, 20, Component.literal(enabled ? "Enabled" : "Disabled"), b -> {
            setter.accept(!enabled);
            rebuildRows();
        }).setVariant(enabled ? PackUiOverlayButton.Variant.SUCCESS : PackUiOverlayButton.Variant.DANGER));
    }

    private void resetDefaults() {
        PackUtilProxyManager mgr = PackUtilProxyManager.get();
        mgr.setTimeoutMs(5000);
        mgr.setThreads(8);
        mgr.setRetries(1);
        mgr.setPruneLatency(2000);
        mgr.setPruneToCount(0);
        mgr.setSortByLatency(true);
        mgr.setPruneDead(true);
        rebuildRows();
    }

    private int selectedOptionIndex(int current, int[] options) {
        if (options.length == 0) return current;
        int index = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < options.length; i++) {
            int distance = Math.abs(options[i] - current);
            if (distance < bestDistance) {
                bestDistance = distance;
                index = i;
            }
        }
        return index;
    }

    private List<String> optionLabels(int[] options, ValueFormatter formatter) {
        List<String> labels = new ArrayList<>();
        for (int option : options) {
            labels.add(formatter.format(option));
        }
        return labels;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int virtualMouseX = PackUtilUiScale.toVirtualInt(mouseX);
        int virtualMouseY = PackUtilUiScale.toVirtualInt(mouseY);
        PackUtilUiScale.pushOverlayScale(graphics);
        try {
            graphics.fill(0, 0, screenWidth(), screenHeight(), BG);
            int panelX = panelX();
            drawPanel(graphics, panelX, 24, PANEL_WIDTH, panelBottom() - 24, PANEL_BG);
            graphics.fill(panelX + 10, 58, panelX + PANEL_WIDTH - 10, panelBottom() - 48, PANEL_BG_SOFT);

            PackUiText.beginManagedLayer(graphics);
            try {
                boolean suppressUnderlyingPointer = PackUiDropdown.shouldSuppressUnderlyingPointer();
                boolean dropdownMenuHovered = PackUiDropdown.isInsideOpenMenu(dropdowns, virtualMouseX, virtualMouseY);
                int buttonMouseX = dropdownMenuHovered || suppressUnderlyingPointer ? Integer.MIN_VALUE : virtualMouseX;
                int buttonMouseY = dropdownMenuHovered || suppressUnderlyingPointer ? Integer.MIN_VALUE : virtualMouseY;
                drawText(graphics, "Proxy Settings", panelX + 14, 34, TEXT, false);
                for (ConfigRow row : rows) {
                    renderRow(graphics, row, panelX);
                }
                for (PackUiOverlayButton button : buttons) {
                    PackUiOverlayButton.renderStyled(graphics, this.font, button, buttonMouseX, buttonMouseY);
                }
                PackUiDropdown.renderButtons(graphics, this.font, dropdowns, virtualMouseX, virtualMouseY);
            } finally {
                PackUiText.endManagedLayer(graphics);
            }
            if (PackUiDropdown.isMenuOpen(dropdowns)) {
                PackUiText.beginManagedLayer(graphics);
                try {
                    PackUiDropdown.renderOpenMenu(graphics, this.font, dropdowns, virtualMouseX, virtualMouseY);
                } finally {
                    PackUiText.endManagedLayer(graphics);
                }
            }
        } finally {
            PackUtilUiScale.popOverlayScale(graphics);
        }
    }

    private void renderRow(GuiGraphicsExtractor graphics, ConfigRow row, int panelX) {
        int x = panelX + 12;
        int y = row.y();
        int w = PANEL_WIDTH - 24;
        int fill = row.toggle() && row.enabled() ? 0x2210261A : 0x18111113;
        int border = row.toggle() && row.enabled() ? SUCCESS : BORDER;
        graphics.fill(x, y, x + w, y + ROW_HEIGHT - 4, fill);
        graphics.fill(x, y, x + w, y + 1, border);
        graphics.fill(x, y + ROW_HEIGHT - 5, x + w, y + ROW_HEIGHT - 4, border);
        graphics.fill(x, y, x + 1, y + ROW_HEIGHT - 4, border);
        graphics.fill(x + w - 1, y, x + w, y + ROW_HEIGHT - 4, border);
        int textMaxWidth = Math.max(80, PANEL_WIDTH - 180);
        drawText(graphics, row.label(), x + 10, y + 8, TEXT, false, textMaxWidth);
        drawText(graphics, row.hint(), x + 10, y + 22, MUTED, false, textMaxWidth);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent virtualEvent = virtualEvent(event);
        if (PackUiDropdown.mouseClicked(dropdowns, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
        if (virtualEvent.button() != 0) return super.mouseClicked(virtualEvent, doubleClick);
        for (PackUiOverlayButton button : buttons) {
            if (PackUiOverlayButton.fireIfHit(button, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
        }
        return super.mouseClicked(virtualEvent, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private MouseButtonEvent virtualEvent(MouseButtonEvent event) {
        return new MouseButtonEvent(PackUtilUiScale.toVirtual(event.x()), PackUtilUiScale.toVirtual(event.y()), new MouseButtonInfo(event.button(), 0));
    }

    private int screenWidth() {
        return Math.max(this.width, PackUtilUiScale.getVirtualScreenWidth());
    }

    private int screenHeight() {
        return Math.max(this.height, PackUtilUiScale.getVirtualScreenHeight());
    }

    private int panelX() {
        return Math.max(12, (screenWidth() - PANEL_WIDTH) / 2);
    }

    private int panelBottom() {
        return 24 + 56 + (ROW_HEIGHT * 7) + 42;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill) {
        graphics.fill(x, y, x + w, y + h, fill);
        graphics.fill(x, y, x + w, y + 1, BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, BORDER);
        graphics.fill(x, y, x + 1, y + h, BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, BORDER);
    }

    private void drawText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, boolean right) {
        drawText(graphics, text, x, y, color, right, Integer.MAX_VALUE);
    }

    private void drawText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, boolean right, int maxWidth) {
        Font renderer = this.font;
        Identifier font = THEME.fontFor(PackUiTone.BODY);
        String display = maxWidth == Integer.MAX_VALUE ? text : PackUiText.trimToWidth(renderer, text, maxWidth, font, color);
        int w = PackUiText.width(renderer, display, font, color);
        int drawX = right ? x - w : x;
        PackUiText.draw(graphics, renderer, display, font, color, drawX, y, false);
    }

    private String formatMs(int value) {
        if (value % 1000 == 0) return (value / 1000) + "s";
        return (value / 1000.0D) + "s";
    }

    @FunctionalInterface
    private interface BooleanConsumer {
        void accept(boolean value);
    }

    @FunctionalInterface
    private interface ValueFormatter {
        String format(int value);
    }

    private record ConfigRow(String label, String value, String hint, int y, boolean toggle, boolean enabled) {
    }
}
