package com.respawnanchorbreaker;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the gold / liquid-glass look. Only plain fills and text, so it works on any renderer. */
final class Gfx {
    static final int GOLD = 0xFFE8B923;
    static final int GOLD_DIM = 0xFFB8921C;
    static final int TEXT = 0xFFF3EEDF;
    static final int MUTED = 0xFF9C957F;
    static final int GREEN = 0xFF5FD068;
    static final int RED = 0xFFE5584F;
    static final int YELLOW = 0xFFF2C94C;

    private Gfx() { }

    static int mulA(int argb, float m) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, m)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static int lerp(int c1, int c2, float t) {
        int a = (int) (((c1 >>> 24) & 0xFF) * (1 - t) + ((c2 >>> 24) & 0xFF) * t);
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Rectangle with the four corner pixels cut off (reads as rounded at GUI scale). */
    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    static void vgrad(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
        for (int i = 0; i < h; i += 2) {
            int bh = Math.min(2, h - i);
            float t = h <= 1 ? 0f : (float) i / (h - 1);
            int c = lerp(top, bottom, t);
            boolean edge = i == 0 || i + bh >= h;
            g.fill(edge ? x + 1 : x, y + i, edge ? x + w - 1 : x + w, y + i + bh, c);
        }
    }

    /** Dark translucent "liquid glass" panel: warm gradient body, thin gold edge and a soft top highlight. */
    static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        int edge = mulA(0x66E8B923, alpha);
        g.fill(x - 1, y + 1, x, y + h - 1, edge);
        g.fill(x + w, y + 1, x + w + 1, y + h - 1, edge);
        g.fill(x + 1, y - 1, x + w - 1, y, edge);
        g.fill(x + 1, y + h, x + w - 1, y + h + 1, edge);
        vgrad(g, x, y, w, h, mulA(0xC4261F12, alpha), mulA(0xC40B0905, alpha));
        g.fill(x + 3, y + 1, x + w - 3, y + 2, mulA(0x46FFF2C8, alpha));
    }

    static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, s, x, y, color, false);
    }

    /** Small trend arrow: dir > 0 up, dir < 0 down, 0 flat. 7 pixels wide. */
    static void arrow(GuiGraphicsExtractor g, int x, int y, int dir, int color) {
        if (dir > 0) {
            g.fill(x + 3, y, x + 4, y + 1, color);
            g.fill(x + 2, y + 1, x + 5, y + 2, color);
            g.fill(x + 1, y + 2, x + 6, y + 3, color);
            g.fill(x, y + 3, x + 7, y + 4, color);
        } else if (dir < 0) {
            g.fill(x, y, x + 7, y + 1, color);
            g.fill(x + 1, y + 1, x + 6, y + 2, color);
            g.fill(x + 2, y + 2, x + 5, y + 3, color);
            g.fill(x + 3, y + 3, x + 4, y + 4, color);
        } else {
            g.fill(x, y + 1, x + 7, y + 3, color);
        }
    }

    static String fit(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (s.length() > 1 && f.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }
}
