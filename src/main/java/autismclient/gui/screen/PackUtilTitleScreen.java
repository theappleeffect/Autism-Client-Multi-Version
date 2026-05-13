package autismclient.gui.screen;

import autismclient.gui.packui.PackUiAssets;
import autismclient.gui.packui.PackUiButton;
import autismclient.gui.packui.PackUiButtonFeedback;
import autismclient.gui.packui.PackUiSizing;
import autismclient.gui.packui.PackUiText;
import autismclient.gui.packui.PackUiTheme;
import autismclient.gui.packui.PackUiTone;
import autismclient.util.PackUtilColors;
import autismclient.util.PackUtilUiScale;
import com.mojang.authlib.minecraft.BanDetails;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ARGB;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class PackUtilTitleScreen extends Screen {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/autism_client_logo.png");
    private static final Identifier BUTTON_CLICK_SOUND_ID = Identifier.fromNamespaceAndPath("autismclient", "gui.main_menu_click");
    private static final SoundEvent BUTTON_CLICK_SOUND = SoundEvent.createVariableRangeEvent(BUTTON_CLICK_SOUND_ID);
    private static final Identifier LANGUAGE_ICON = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/icons/language.png");
    private static final Identifier ACCESSIBILITY_ICON = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/icons/accessibility.png");
    private static final Identifier ESSENTIAL_ICON = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/icons/essential.png");
    private static final Identifier TEXT_SINGLEPLAYER = buttonText("singleplayer");
    private static final Identifier TEXT_MULTIPLAYER = buttonText("multiplayer");
    private static final Identifier TEXT_REALMS = buttonText("minecraft_realms");
    private static final Identifier TEXT_OPTIONS = buttonText("options");
    private static final Identifier TEXT_QUIT = buttonText("quit_game");
    private static final Identifier MODMENU_ICON = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/icons/modmenu.png");
    private static final int LOGO_TEXTURE_WIDTH = 516;
    private static final int LOGO_TEXTURE_HEIGHT = 144;
    private static final int LOGO_MAX_WIDTH = 320;
    private static final int LOGO_MAX_HEIGHT = 72;
    private static final int ICON_TEXTURE_SIZE = 32;
    private static final int BUTTON_TEXT_TARGET_HEIGHT = 14;
    private static final Component TITLE = Component.translatable("narrator.screen.title");

    private final PackUiTheme theme = new PackUiTheme();
    private final List<MenuButton> buttons = new ArrayList<>();
    private final String modCountText = createModCountText();
    private final boolean modMenuLoaded = FabricLoader.getInstance().isModLoaded("modmenu");
    private final boolean essentialLoaded = FabricLoader.getInstance().isModLoaded("essential");
    private List<MeteorCreditLine> meteorCredits = List.of();
    private boolean meteorCreditsLoadFailed;
    private int footerY;

    public PackUtilTitleScreen() {
        super(TITLE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.minecraft.gameRenderer.getPanorama().extractRenderState(graphics, this.width, this.height, this.panoramaShouldSpin());

        float uiMouseX = (float) PackUtilUiScale.toVirtual(mouseX);
        float uiMouseY = (float) PackUtilUiScale.toVirtual(mouseY);
        layout();

        PackUtilUiScale.pushOverlayScale(graphics);
        PackUiText.beginManagedLayer(graphics);
        try {
            renderLogo(graphics);
            renderMeteorCredits(graphics);
            Component hoveredTooltip = null;
            for (MenuButton button : buttons) {
                button.render(graphics, uiMouseX, uiMouseY, delta);
                if (button.contains(uiMouseX, uiMouseY)) {
                    graphics.requestCursor(button.enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
                    if (button.tooltip != null) {
                        hoveredTooltip = button.tooltip;
                    }
                }
            }
            renderFooter(graphics, uiMouseX, uiMouseY);
            if (hoveredTooltip != null) {
                renderCustomTooltip(graphics, hoveredTooltip, uiMouseX, uiMouseY);
            }
        } finally {
            PackUiText.endManagedLayer(graphics);
            PackUtilUiScale.popOverlayScale(graphics);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return false;

        float uiMouseX = (float) PackUtilUiScale.toVirtual(event.x());
        float uiMouseY = (float) PackUtilUiScale.toVirtual(event.y());
        layout();

        for (MenuButton button : buttons) {
            if (button.click(uiMouseX, uiMouseY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void removed() {
        PackUiText.discardPendingOverlayText();
    }

    @Override
    protected boolean panoramaShouldSpin() {
        return true;
    }

    private void layout() {
        int screenW = PackUtilUiScale.getVirtualScreenWidth();
        int screenH = PackUtilUiScale.getVirtualScreenHeight();
        int centerX = screenW / 2;
        int menuTop = Math.max(82, screenH / 4 + 48);
        int fullW = 200;
        int halfW = 98;
        int rowH = 20;
        int gap = 24;
        Component disabledReason = multiplayerDisabledReason();
        boolean multiplayerAllowed = disabledReason == null;

        buttons.clear();
        buttons.add(new MenuButton(centerX - 100, menuTop, fullW, rowH, Component.translatable("menu.singleplayer"), null, TEXT_SINGLEPLAYER, 248, 24, 124, 12, true,
            () -> this.minecraft.setScreen(new SelectWorldScreen(this))));
        buttons.add(new MenuButton(centerX - 100, menuTop + gap, fullW, rowH, Component.translatable("menu.multiplayer"), null, TEXT_MULTIPLAYER, 222, 24, 111, 12, multiplayerAllowed,
            () -> {
                Screen next = this.minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this);
                this.minecraft.setScreen(next);
            }).withTooltip(disabledReason));
        buttons.add(new MenuButton(centerX - 100, menuTop + (gap * 2), fullW, rowH, Component.translatable("menu.online"), null, TEXT_REALMS, 136, 24, 68, 12, multiplayerAllowed,
            () -> this.minecraft.setScreen(new RealmsMainScreen(this))).withTooltip(disabledReason));

        int bottomY = menuTop + 84;
        if (modMenuLoaded) {
            buttons.add(new MenuButton(centerX - 148, bottomY, 20, 20, Component.literal("Mod Menu"), MODMENU_ICON, null, 0, 0, 0, 0, true,
                () -> openModMenu()).withTooltip(Component.literal("Mod Menu")));
        }
        buttons.add(new MenuButton(centerX - 124, bottomY, 20, 20, Component.translatable("options.language"), LANGUAGE_ICON, null, 0, 0, 0, 0, true,
            () -> this.minecraft.setScreen(new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager()))).withTooltip(Component.literal("Language")));
        buttons.add(new MenuButton(centerX - 100, bottomY, halfW, rowH, Component.translatable("menu.options"), null, TEXT_OPTIONS, 138, 24, 69, 12, true,
            () -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options, false))));
        buttons.add(new MenuButton(centerX + 2, bottomY, halfW, rowH, Component.translatable("menu.quit"), null, TEXT_QUIT, 74, 24, 37, 12, true,
            () -> this.minecraft.stop()));
        buttons.add(new MenuButton(centerX + 104, bottomY, 20, 20, Component.translatable("options.accessibility"), ACCESSIBILITY_ICON, null, 0, 0, 0, 0, true,
            () -> this.minecraft.setScreen(new AccessibilityOptionsScreen(this, this.minecraft.options))).withTooltip(Component.literal("Accessibility")));
        if (essentialLoaded) {
            buttons.add(new MenuButton(centerX + 128, bottomY, 20, 20, Component.literal("Essential"), ESSENTIAL_ICON, null, 0, 0, 0, 0, true,
                () -> openEssential()).withTooltip(Component.literal("Essential")));
        }

        int footerHeight = Math.max(10, theme.fontHeight(PackUiTone.BODY));
        this.footerY = Math.max(2, screenH - footerHeight - 2);
    }

    private Component multiplayerDisabledReason() {
        if (this.minecraft.allowsMultiplayer()) return null;
        if (this.minecraft.isNameBanned()) return Component.translatable("title.multiplayer.disabled.banned.name");

        BanDetails multiplayerBan = this.minecraft.multiplayerBan();
        if (multiplayerBan != null) {
            return multiplayerBan.expires() != null
                ? Component.translatable("title.multiplayer.disabled.banned.temporary")
                : Component.translatable("title.multiplayer.disabled.banned.permanent");
        }
        return Component.translatable("title.multiplayer.disabled");
    }

    private void renderLogo(GuiGraphicsExtractor graphics) {
        int screenW = PackUtilUiScale.getVirtualScreenWidth();
        int screenH = PackUtilUiScale.getVirtualScreenHeight();
        int menuTop = Math.max(82, screenH / 4 + 48);
        int maxWidth = Math.min(LOGO_MAX_WIDTH, Math.max(180, screenW - 40));
        float scale = Math.min(maxWidth / (float) LOGO_TEXTURE_WIDTH, LOGO_MAX_HEIGHT / (float) LOGO_TEXTURE_HEIGHT);
        int drawW = Math.round(LOGO_TEXTURE_WIDTH * scale);
        int drawH = Math.round(LOGO_TEXTURE_HEIGHT * scale);
        int x = screenW / 2 - drawW / 2;
        int y = Math.max(12, menuTop - drawH - 18);

        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            LOGO,
            x,
            y,
            0.0F,
            0.0F,
            drawW,
            drawH,
            LOGO_TEXTURE_WIDTH,
            LOGO_TEXTURE_HEIGHT,
            LOGO_TEXTURE_WIDTH,
            LOGO_TEXTURE_HEIGHT,
            ARGB.white(1.0F)
        );
    }

    private void renderFooter(GuiGraphicsExtractor graphics, float mouseX, float mouseY) {
        PackUiText.draw(graphics, this.font, modCountText, PackUiAssets.FONT_BODY, theme.color(PackUiTone.MUTED), 2, footerY, false);
    }

    private void renderMeteorCredits(GuiGraphicsExtractor graphics) {
        List<MeteorCreditLine> credits = getMeteorCredits();
        if (credits.isEmpty()) return;

        int screenW = PackUtilUiScale.getVirtualScreenWidth();
        int y = 3;
        int lineGap = PackUiText.fontHeight(PackUiAssets.FONT_BODY) + 2;
        for (MeteorCreditLine credit : credits) {
            int x = screenW - 3 - credit.width(this.font);
            for (MeteorCreditSegment segment : credit.segments()) {
                if (!segment.text().isEmpty()) {
                    PackUiText.draw(graphics, this.font, segment.text(), PackUiAssets.FONT_BODY, segment.color(), x, y, false);
                    x += PackUiText.width(this.font, segment.text(), PackUiAssets.FONT_BODY, segment.color());
                }
            }
            y += lineGap;
        }
    }

    private List<MeteorCreditLine> getMeteorCredits() {
        if (!meteorCredits.isEmpty() || meteorCreditsLoadFailed) return meteorCredits;
        if (!FabricLoader.getInstance().isModLoaded("meteor-client")) return meteorCredits;

        try {
            Class<?> addonManagerClass = Class.forName("meteordevelopment.meteorclient.addons.AddonManager");
            Field addonsField = addonManagerClass.getField("ADDONS");
            Object value = addonsField.get(null);
            if (!(value instanceof Iterable<?> addons)) return meteorCredits;

            List<MeteorCreditLine> loaded = new ArrayList<>();
            for (Object addon : addons) {
                MeteorCreditLine line = meteorCreditLine(addon);
                if (line != null) loaded.add(line);
            }
            meteorCredits = List.copyOf(loaded);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            meteorCreditsLoadFailed = true;
        }
        return meteorCredits;
    }

    private static MeteorCreditLine meteorCreditLine(Object addon) throws ReflectiveOperationException {
        if (addon == null) return null;
        Class<?> addonClass = addon.getClass();
        String name = stringField(addonClass, addon, "name");
        String[] authors = authorsField(addonClass, addon);
        if (name == null || name.isBlank() || authors.length == 0) return null;

        int addonColor = addonColor(addonClass, addon);
        List<MeteorCreditSegment> segments = new ArrayList<>();
        segments.add(new MeteorCreditSegment(name, addonColor));
        segments.add(new MeteorCreditSegment(" by ", 0xFFAAAAAA));
        for (int i = 0; i < authors.length; i++) {
            if (i > 0) segments.add(new MeteorCreditSegment(i == authors.length - 1 ? " & " : ", ", 0xFFAAAAAA));
            segments.add(new MeteorCreditSegment(authors[i], 0xFFFFFFFF));
        }
        return new MeteorCreditLine(List.copyOf(segments));
    }

    private static String stringField(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        Object value = type.getField(name).get(instance);
        return value instanceof String text ? text : null;
    }

    private static String[] authorsField(Class<?> type, Object instance) throws ReflectiveOperationException {
        Object value = type.getField("authors").get(instance);
        if (!(value instanceof String[] authors)) return new String[0];
        return authors;
    }

    private static int addonColor(Class<?> type, Object instance) throws ReflectiveOperationException {
        Object color = type.getField("color").get(instance);
        if (color == null) return 0xFFFFFFFF;
        Method getPacked = color.getClass().getMethod("getPacked");
        Object packed = getPacked.invoke(color);
        return packed instanceof Integer intColor ? intColor : 0xFFFFFFFF;
    }

    private static Identifier buttonText(String name) {
        return Identifier.fromNamespaceAndPath("autismclient", "textures/gui/title/button_text/" + name + ".png");
    }

    private void openModMenu() {
        try {
            Class<?> apiClass = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi");
            Method createMethod = apiClass.getMethod("createModsScreen", Screen.class);
            Screen modsScreen = (Screen) createMethod.invoke(null, this);
            this.minecraft.setScreen(modsScreen);
        } catch (Exception e) {

        }
    }

    private void openEssential() {
        try {
            Class<?> clazz = Class.forName("gg.essential.gui.modals.QuickAccessModal");
            Object companion = clazz.getDeclaredField("Companion").get(null);
            Method open = companion.getClass().getDeclaredMethod("open");
            open.setAccessible(true);
            open.invoke(companion);
        } catch (Exception e) {

        }
    }

    private void renderCustomTooltip(GuiGraphicsExtractor graphics, Component tooltip, float uiMouseX, float uiMouseY) {
        String text = tooltip.getString();
        int textColor = theme.color(PackUiTone.BODY);
        Identifier font = theme.fontFor(PackUiTone.BODY);
        int textW = PackUiText.width(this.font, text, font, textColor);
        int padding = 4;
        int width = textW + padding * 2;
        int height = theme.fontHeight(PackUiTone.BODY) + padding * 2;

        int drawX = Math.round(uiMouseX + 8);
        int drawY = Math.round(uiMouseY - height - 4);

        int screenW = PackUtilUiScale.getVirtualScreenWidth();
        int screenH = PackUtilUiScale.getVirtualScreenHeight();
        if (drawX + width > screenW) drawX = screenW - width - 2;
        if (drawY < 0) drawY = Math.round(uiMouseY + 16);
        if (drawY + height > screenH) drawY = screenH - height - 2;

        PackUiText.fill(graphics, drawX, drawY, drawX + width, drawY + height, PackUtilColors.tooltipBg());
        PackUiText.fill(graphics, drawX, drawY, drawX + width, drawY + 1, 0xFFFF4A4A);
        PackUiText.fill(graphics, drawX, drawY + height - 1, drawX + width, drawY + height, 0xFFFF4A4A);
        PackUiText.fill(graphics, drawX, drawY, drawX + 1, drawY + height, 0xFFFF4A4A);
        PackUiText.fill(graphics, drawX + width - 1, drawY, drawX + width, drawY + height, 0xFFFF4A4A);

        PackUiText.draw(graphics, this.font, text, font, 0xFFF0ECE7, drawX + padding, drawY + padding, false);
    }

    private static String createModCountText() {
        int modCount = FabricLoader.getInstance().getAllMods().size();
        return modCount + (modCount == 1 ? " Mod" : " Mods");
    }

    private record MeteorCreditLine(List<MeteorCreditSegment> segments) {
        private int width(net.minecraft.client.gui.Font font) {
            int width = 0;
            for (MeteorCreditSegment segment : segments) {
                width += PackUiText.width(font, segment.text(), PackUiAssets.FONT_BODY, segment.color());
            }
            return width;
        }
    }

    private record MeteorCreditSegment(String text, int color) {
    }

    private final class MenuButton {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final Component label;
        private final Identifier icon;
        private final Identifier labelTexture;
        private final int labelTextureWidth;
        private final int labelTextureHeight;
        private final int labelDrawWidth;
        private final int labelDrawHeight;
        private final boolean enabled;
        private final Runnable onPress;
        private Component tooltip;

        private MenuButton(int x, int y, int width, int height, Component label, Identifier icon, Identifier labelTexture,
                           int labelTextureWidth, int labelTextureHeight, int labelDrawWidth, int labelDrawHeight, boolean enabled, Runnable onPress) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.icon = icon;
            this.labelTexture = labelTexture;
            this.labelTextureWidth = labelTextureWidth;
            this.labelTextureHeight = labelTextureHeight;
            this.labelDrawWidth = labelDrawWidth;
            this.labelDrawHeight = labelDrawHeight;
            this.enabled = enabled;
            this.onPress = onPress;
        }

        private MenuButton withTooltip(Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        private boolean contains(float mouseX, float mouseY) {
            return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        }

        private boolean click(float mouseX, float mouseY) {
            if (!contains(mouseX, mouseY)) return false;
            if (!enabled) return true;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(BUTTON_CLICK_SOUND, 1.0F, 0.7F));
            if (onPress != null) onPress.run();
            return true;
        }

        private void render(GuiGraphicsExtractor graphics, float mouseX, float mouseY, float delta) {
            boolean hovered = enabled && contains(mouseX, mouseY);
            PackUiButton.Variant variant = enabled ? PackUiButton.Variant.SECONDARY : PackUiButton.Variant.GHOST;
            PackUiButtonFeedback feedback = feedback();
            float hover = feedback.update(hovered, false, mouseX - x, mouseY - y, width, height);
            int fill = enabled ? theme.buttonFill(variant) : theme.buttonFill(variant, false);
            int border = enabled ? theme.buttonBorder(variant) : theme.buttonBorderInactive(variant);
            border = PackUiSizing.lerpColor(border, theme.buttonBorderGlow(variant), Math.min(1.0f, hover * 0.35f));

            graphics.fill(x, y, x + width, y + height, fill);
            if (hover > 0.0f) {
                int hoverAlpha = Math.min(42, Math.round(hover * 24.0f));
                graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, (hoverAlpha << 24) | 0x00FF3B3B);
            }
            drawBorder(graphics, x, y, width, height, border);

            if (icon != null) {
                int iconSize = icon == MODMENU_ICON ? 14 : 16;
                int iconX = x + (width - iconSize) / 2;
                int iconY = y + (height - iconSize) / 2;
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    icon,
                    iconX,
                    iconY,
                    0.0F,
                    0.0F,
                    iconSize,
                    iconSize,
                    ICON_TEXTURE_SIZE,
                    ICON_TEXTURE_SIZE,
                    ICON_TEXTURE_SIZE,
                    ICON_TEXTURE_SIZE,
                    enabled ? 0xFFFFFFFF : 0xFF8D7777
                );
                return;
            }

            if (labelTexture != null && labelTextureWidth > 0 && labelTextureHeight > 0 && labelDrawWidth > 0 && labelDrawHeight > 0) {
                int textColor = enabled ? theme.buttonTextColor(variant) : theme.buttonTextColorInactive(variant);
                int maxW = Math.max(1, width - 16);
                int maxH = Math.max(1, Math.min(BUTTON_TEXT_TARGET_HEIGHT, height - 6));
                float scale = Math.min(1.0f, Math.min(maxW / (float) labelDrawWidth, maxH / (float) labelDrawHeight));
                int drawW = Math.max(1, Math.round(labelDrawWidth * scale));
                int drawH = Math.max(1, Math.round(labelDrawHeight * scale));
                int textX = PackUiSizing.centerInside(x, width, drawW);
                int textY = PackUiSizing.centerInside(y, height, drawH);
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    labelTexture,
                    textX,
                    textY,
                    0.0F,
                    0.0F,
                    drawW,
                    drawH,
                    labelTextureWidth,
                    labelTextureHeight,
                    labelTextureWidth,
                    labelTextureHeight,
                    textColor
                );
            } else if (label != null) {
                int textColor = enabled ? theme.buttonTextColor(variant) : theme.buttonTextColorInactive(variant);
                int textW = PackUtilTitleScreen.this.font.width(label);
                int textX = x + (width - textW) / 2;
                int textY = y + (height - PackUtilTitleScreen.this.font.lineHeight) / 2 + 1;
                graphics.text(PackUtilTitleScreen.this.font, label, textX, textY, textColor, false);
            }
        }

        private PackUiButtonFeedback feedback() {
            return PackUiButtonFeedback.forKey("title-menu:" + label.getString() + ':' + x + ':' + y + ':' + width + ':' + height);
        }
    }

    private static void drawBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
