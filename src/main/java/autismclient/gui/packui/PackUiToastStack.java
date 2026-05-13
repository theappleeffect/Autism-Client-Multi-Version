package autismclient.gui.packui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class PackUiToastStack {
    private static final long LIFETIME_MS = 1800L;
    private static final float ENTER_MS = 140.0f;
    private static final float EXIT_MS = 220.0f;
    private static final int MAX_VISIBLE = 4;
    private static final int GAP = 4;
    private static final int HEIGHT = 18;

    private final List<ToastEntry> toasts = new ArrayList<>();

    public void show(String message, int accentColor) {
        if (message == null || message.isBlank()) return;
        long nowNanos = System.nanoTime();
        prune(nowNanos);
        if (toasts.size() >= MAX_VISIBLE) {
            toasts.remove(0);
        }
        toasts.add(new ToastEntry(message, nowNanos, accentColor));
    }

    public boolean hasVisibleToasts() {
        prune(System.nanoTime());
        return !toasts.isEmpty();
    }

    public void clear() {
        toasts.clear();
    }

    public void render(GuiGraphicsExtractor graphics, Font font, PackUiTheme theme, int anchorX, int anchorY, int anchorWidth) {
        if (graphics == null || font == null || theme == null || anchorWidth <= 0) return;
        long nowNanos = System.nanoTime();
        prune(nowNanos);
        if (toasts.isEmpty()) return;

        int y = anchorY;
        Identifier bodyFont = theme.fontFor(PackUiTone.BODY);
        int bodyColor = theme.color(PackUiTone.BODY);
        int bodyHeight = theme.fontHeight(PackUiTone.BODY);

        for (int i = toasts.size() - 1; i >= 0; i--) {
            ToastEntry toast = toasts.get(i);
            float ageMs = Math.max(0.0f, (nowNanos - toast.shownAtNanos()) / 1_000_000.0f);
            float enter = clamp01(ageMs / ENTER_MS);
            float exit = clamp01((LIFETIME_MS - ageMs) / EXIT_MS);
            float alpha = Math.min(easeOutCubic(enter), easeOutCubic(exit));
            if (alpha <= 0.001f) continue;

            float scale = 0.90f + (0.10f * easeOutBack(enter));
            float offsetY = ((1.0f - easeOutCubic(enter)) * -10.0f) + ((1.0f - exit) * 4.0f);
            int maxToastWidth = Math.min(anchorWidth, 260);
            String trimmed = PackUiText.trimToWidth(font, toast.message(), maxToastWidth - 14, bodyFont, bodyColor);
            int textWidth = PackUiText.width(font, trimmed, bodyFont, bodyColor);
            int toastWidth = Math.max(124, Math.min(maxToastWidth, textWidth + 18));
            int drawX = anchorX + Math.max(0, (anchorWidth - toastWidth) / 2);
            int drawY = Math.round(y + offsetY);

            float centerX = drawX + (toastWidth / 2.0f);
            float centerY = drawY + (HEIGHT / 2.0f);
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX, centerY);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(-centerX, -centerY);

            int fill = PackUiRenderContext.applyAlpha(0xD6121014, alpha);
            int border = PackUiRenderContext.applyAlpha(toast.accentColor(), alpha);
            int highlight = PackUiRenderContext.applyAlpha(0x24FFFFFF, alpha);
            int textColor = PackUiRenderContext.applyAlpha(0xFFF4F4F4, alpha);

            PackUiText.fill(graphics, drawX, drawY, drawX + toastWidth, drawY + HEIGHT, fill);
            PackUiText.fill(graphics, drawX, drawY, drawX + toastWidth, drawY + 1, border);
            PackUiText.fill(graphics, drawX, drawY + HEIGHT - 1, drawX + toastWidth, drawY + HEIGHT, border);
            PackUiText.fill(graphics, drawX, drawY, drawX + 1, drawY + HEIGHT, border);
            PackUiText.fill(graphics, drawX + toastWidth - 1, drawY, drawX + toastWidth, drawY + HEIGHT, border);
            PackUiText.fill(graphics, drawX + 1, drawY + 1, drawX + toastWidth - 1, drawY + 2, highlight);
            int textY = PackUiSizing.alignTextY(drawY, HEIGHT, bodyHeight, theme.bodyTextNudge());
            PackUiText.draw(graphics, font, trimmed, bodyFont, textColor, drawX + 8, textY, false);

            graphics.pose().popMatrix();
            y += HEIGHT + GAP;
        }
    }

    private void prune(long nowNanos) {
        toasts.removeIf(toast -> (nowNanos - toast.shownAtNanos()) / 1_000_000L >= LIFETIME_MS);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float easeOutCubic(float value) {
        float t = clamp01(value);
        float inv = 1.0f - t;
        return 1.0f - (inv * inv * inv);
    }

    private static float easeOutBack(float value) {
        float t = clamp01(value);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float x = t - 1.0f;
        return 1.0f + (c3 * x * x * x) + (c1 * x * x);
    }

    private record ToastEntry(String message, long shownAtNanos, int accentColor) {
    }
}
