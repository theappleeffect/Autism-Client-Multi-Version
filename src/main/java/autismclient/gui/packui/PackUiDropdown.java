package autismclient.gui.packui;

import autismclient.util.PackUtilText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.IntConsumer;

public final class PackUiDropdown {
    private static final PackUiTheme THEME = new PackUiTheme();
    private static final int OPTION_HEIGHT = 20;
    private static boolean suppressUnderlyingPointerUntilRelease;

    public boolean active = true;
    public boolean visible = true;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final List<String> options;
    private final IntConsumer onSelect;
    private int selectedIndex;
    private boolean open;

    public PackUiDropdown(int x, int y, int width, int height, List<String> options, int selectedIndex, IntConsumer onSelect) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.selectedIndex = clampIndex(selectedIndex, this.options.size());
        this.onSelect = onSelect;
    }

    public static void renderAll(GuiGraphicsExtractor context, Font font, List<PackUiDropdown> dropdowns, int mouseX, int mouseY) {
        renderButtons(context, font, dropdowns, mouseX, mouseY);
        renderOpenMenu(context, font, dropdowns, mouseX, mouseY);
    }

    public static void renderButtons(GuiGraphicsExtractor context, Font font, List<PackUiDropdown> dropdowns, int mouseX, int mouseY) {
        if (dropdowns == null) return;
        boolean menuOpen = isMenuOpen(dropdowns);
        boolean suppressPointer = shouldSuppressUnderlyingPointer();
        for (PackUiDropdown dropdown : dropdowns) {
            if (dropdown == null || !dropdown.visible) continue;
            boolean suppressHover = suppressPointer || (menuOpen && (!dropdown.open || dropdown.containsOpenMenu(mouseX, mouseY)));
            int buttonMouseX = suppressHover ? Integer.MIN_VALUE : mouseX;
            int buttonMouseY = suppressHover ? Integer.MIN_VALUE : mouseY;
            dropdown.renderButton(context, font, buttonMouseX, buttonMouseY);
        }
    }

    public static void renderOpenMenu(GuiGraphicsExtractor context, Font font, List<PackUiDropdown> dropdowns, int mouseX, int mouseY) {
        if (dropdowns == null) return;
        for (PackUiDropdown dropdown : dropdowns) {
            if (dropdown != null && dropdown.visible && dropdown.open) {
                dropdown.renderMenu(context, font, mouseX, mouseY);
                return;
            }
        }
    }

    public static boolean isMenuOpen(List<PackUiDropdown> dropdowns) {
        if (dropdowns == null) return false;
        for (PackUiDropdown dropdown : dropdowns) {
            if (dropdown != null && dropdown.visible && dropdown.open) return true;
        }
        return false;
    }

    public static boolean isInsideOpenMenu(List<PackUiDropdown> dropdowns, double mouseX, double mouseY) {
        if (dropdowns == null) return false;
        for (PackUiDropdown dropdown : dropdowns) {
            if (dropdown != null && dropdown.visible && dropdown.open && dropdown.containsOpenMenu(mouseX, mouseY)) return true;
        }
        return false;
    }

    public static boolean shouldSuppressUnderlyingPointer() {
        if (!suppressUnderlyingPointerUntilRelease) return false;
        if (!PackUiButtonFeedback.isPrimaryPointerDown()) {
            suppressUnderlyingPointerUntilRelease = false;
            return false;
        }
        return true;
    }

    public static boolean mouseClicked(List<PackUiDropdown> dropdowns, double mouseX, double mouseY, int mouseButton) {
        if (dropdowns == null || mouseButton != 0) return false;
        PackUiDropdown openDropdown = null;
        for (PackUiDropdown dropdown : dropdowns) {
            if (dropdown != null && dropdown.visible && dropdown.open) {
                openDropdown = dropdown;
                break;
            }
        }
        if (openDropdown != null) {
            suppressUnderlyingPointerUntilRelease = true;
            if (openDropdown.handleOpenClick(mouseX, mouseY)) return true;
            openDropdown.open = false;
            return true;
        }
        for (int i = dropdowns.size() - 1; i >= 0; i--) {
            PackUiDropdown dropdown = dropdowns.get(i);
            if (dropdown == null || !dropdown.visible || !dropdown.active) continue;
            if (dropdown.contains(mouseX, mouseY)) {
                dropdown.open = !dropdown.open;
                return true;
            }
        }
        return false;
    }

    public void close() {
        open = false;
    }

    private void renderButton(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        boolean hovered = active && contains(mouseX, mouseY);
        int bg = hovered ? 0xFF241016 : 0xFF1B0D12;
        int border = hovered ? THEME.overlayButtonBorderGlow(PackUiOverlayButton.Variant.PRIMARY) : THEME.overlayButtonBorder(PackUiOverlayButton.Variant.PRIMARY, active);
        int textColor = THEME.overlayButtonTextColor(PackUiOverlayButton.Variant.PRIMARY, active);
        drawBox(context, x, y, width, height, bg, border);
        if (hovered) drawFill(context, x + 1, y + 1, x + width - 1, y + height - 1, 0xFF2A1218);
        Identifier fontId = THEME.fontFor(PackUiTone.BODY);
        String label = PackUtilText.sanitizeUiLabel(selectedLabel());
        String display = PackUiText.trimToWidth(font, label, Math.max(1, width - 20), fontId, textColor);
        int textY = PackUiSizing.alignTextY(y, height, THEME.fontHeight(PackUiTone.BODY), THEME.buttonTextNudge());
        PackUiText.draw(context, font, display, fontId, textColor, x + 7, textY, false);
        String arrow = open ? "▲" : "▼";
        int arrowWidth = PackUiText.width(font, arrow, fontId, textColor);
        PackUiText.draw(context, font, arrow, fontId, textColor, x + width - arrowWidth - 7, textY, false);
    }

    private void renderMenu(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        if (!open || options.isEmpty()) return;
        int menuY = y + height + 2;
        int menuHeight = options.size() * OPTION_HEIGHT;
        drawBox(context, x, menuY, width, menuHeight, 0xFF141417, THEME.overlayButtonBorder(PackUiOverlayButton.Variant.PRIMARY, true));
        PackUiText.addTextOccluder(context, x, menuY, x + width, menuY + menuHeight);
        Identifier fontId = THEME.fontFor(PackUiTone.BODY);
        for (int i = 0; i < options.size(); i++) {
            int optionY = menuY + (i * OPTION_HEIGHT);
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= optionY && mouseY < optionY + OPTION_HEIGHT;
            if (selected) drawFill(context, x + 1, optionY + 1, x + width - 1, optionY + OPTION_HEIGHT - 1, 0xFF24366B);
            else if (hovered) drawFill(context, x + 1, optionY + 1, x + width - 1, optionY + OPTION_HEIGHT - 1, 0xFF241016);
            int textColor = selected ? 0xFFE2E8FF : 0xFFF2F2F2;
            String label = PackUtilText.sanitizeUiLabel(options.get(i));
            String display = PackUiText.trimToWidth(font, label, Math.max(1, width - 14), fontId, textColor);
            int textY = PackUiSizing.alignTextY(optionY, OPTION_HEIGHT, THEME.fontHeight(PackUiTone.BODY), THEME.buttonTextNudge());
            PackUiText.draw(context, font, display, fontId, textColor, x + 7, textY, false);
        }
    }

    private boolean handleOpenClick(double mouseX, double mouseY) {
        if (contains(mouseX, mouseY)) {
            open = false;
            return true;
        }
        int menuY = y + height + 2;
        for (int i = 0; i < options.size(); i++) {
            int optionY = menuY + (i * OPTION_HEIGHT);
            if (mouseX >= x && mouseX < x + width && mouseY >= optionY && mouseY < optionY + OPTION_HEIGHT) {
                selectedIndex = i;
                open = false;
                if (onSelect != null) onSelect.accept(i);
                return true;
            }
        }
        return false;
    }

    private void drawBox(GuiGraphicsExtractor context, int x, int y, int width, int height, int fill, int border) {
        drawFill(context, x, y, x + width, y + height, fill);
        drawFill(context, x, y, x + width, y + 1, border);
        drawFill(context, x, y + height - 1, x + width, y + height, border);
        drawFill(context, x, y, x + 1, y + height, border);
        drawFill(context, x + width - 1, y, x + width, y + height, border);
    }

    private void drawFill(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, int color) {
        if (open) PackUiText.fill(context, x0, y0, x1, y1, color);
        else context.fill(x0, y0, x1, y1, color);
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean containsOpenMenu(double mouseX, double mouseY) {
        if (!open || options.isEmpty()) return false;
        int menuY = y + height + 2;
        int menuHeight = options.size() * OPTION_HEIGHT;
        return mouseX >= x && mouseX < x + width && mouseY >= menuY && mouseY < menuY + menuHeight;
    }

    private String selectedLabel() {
        if (options.isEmpty()) return "";
        return options.get(clampIndex(selectedIndex, options.size()));
    }

    private static int clampIndex(int index, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(size - 1, index));
    }
}
