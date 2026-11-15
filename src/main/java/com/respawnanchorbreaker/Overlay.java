package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** Small draggable credit card drawn on the HUD (drag it while the Right Ctrl menu is open). */
public final class Overlay {
    private static final int BORDER = 0xFF2A2A2A;
    private static final int PANEL = 0xE6101010;
    private static final int ACCENT = 0xFFC8A02A;
    private static final int TEXT = 0xFFEAEAEA;
    private static final int MUTED = 0xFF8A8A8A;
    private static final int GREEN = 0xFF58C46B;
    private static final int RED = 0xFFD9534F;
    private static final int H = 58;

    private static final String TITLE = "Anchor Addon";
    private static final String GH_LABEL = "GitHub ";
    private static final String GH_NAME = "sxlxs420";
    private static final String CLIENT = "Temple Client + Baritone";
    private static final String SITE = "temple.nz";
    private static final String CODE_LABEL = "  code ";
    private static final String CODE = "wks";
    private static final String OFF = "  15% off";

    private Overlay() { }

    private static int width(Font f) {
        int w = f.width(TITLE) + 44; // room for the ON/OFF badge
        w = Math.max(w, f.width(GH_LABEL + GH_NAME));
        w = Math.max(w, f.width(CLIENT));
        w = Math.max(w, f.width(SITE + CODE_LABEL + CODE + OFF));
        return w + 16;
    }

    public static int x(Minecraft mc) {
        int max = Math.max(0, mc.getWindow().getGuiScaledWidth() - width(mc.font));
        return Mth.clamp(Config.get().overlayX, 0, max);
    }

    public static int y(Minecraft mc) {
        int max = Math.max(0, mc.getWindow().getGuiScaledHeight() - H);
        return Mth.clamp(Config.get().overlayY, 0, max);
    }

    public static boolean hit(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.get().showOverlay) return false;
        int x = x(mc), y = y(mc);
        return mx >= x && mx < x + width(mc.font) && my >= y && my < y + H;
    }

    public static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Config c = Config.get();
        if (!c.showOverlay) return;
        Font f = mc.font;
        int w = width(f), x = x(mc), y = y(mc);

        g.fill(x - 1, y - 1, x + w + 1, y + H + 1, BORDER);
        g.fill(x, y, x + w, y + H, PANEL);
        g.fill(x, y, x + w, y + 1, ACCENT);

        g.text(f, TITLE, x + 8, y + 6, ACCENT, false);
        String st = c.enabled ? "ON" : "OFF";
        int stW = f.width(st);
        g.text(f, st, x + w - 8 - stW, y + 6, c.enabled ? GREEN : RED, false);
        g.fill(x + w - 8 - stW - 7, y + 8, x + w - 8 - stW - 3, y + 12, c.enabled ? GREEN : RED);
        g.fill(x + 8, y + 17, x + w - 8, y + 18, BORDER);

        g.text(f, GH_LABEL, x + 8, y + 23, MUTED, false);
        g.text(f, GH_NAME, x + 8 + f.width(GH_LABEL), y + 23, TEXT, false);
        g.text(f, CLIENT, x + 8, y + 34, TEXT, false);

        int tx = x + 8;
        g.text(f, SITE, tx, y + 45, TEXT, false);
        tx += f.width(SITE);
        g.text(f, CODE_LABEL, tx, y + 45, MUTED, false);
        tx += f.width(CODE_LABEL);
        g.text(f, CODE, tx, y + 45, ACCENT, false);
        tx += f.width(CODE);
        g.text(f, OFF, tx, y + 45, MUTED, false);
    }
}
