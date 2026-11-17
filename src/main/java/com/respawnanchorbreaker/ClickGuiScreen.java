package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Main menu: one glass panel per category listing its modules, plus the activity log. Left-click a module to
 * toggle it, right-click (or middle-click) to open its settings. Overlays can be dragged anywhere.
 */
public final class ClickGuiScreen extends Screen {
    private static final int RH = 14;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final long openedAt = System.currentTimeMillis();
    private Overlays.Id dragging;
    private double dragOffX, dragOffY;
    private int logScroll;
    private int panelW, panelX0, panelY0, logX, logW, logH;

    public ClickGuiScreen() {
        super(Component.literal("Anchor Addon"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }

    private float anim() {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 200f);
        return t * t * (3 - 2 * t);
    }

    private void layout() {
        int n = Modules.CATEGORIES.length;
        logW = Mth.clamp(this.width / 4, 170, 260);
        int avail = this.width - logW - 48;
        panelW = Mth.clamp(avail / n - 8, 100, 134);
        int total = n * (panelW + 8) + logW;
        panelX0 = Math.max(4, (this.width - total) / 2);
        panelY0 = 40;
        logX = panelX0 + n * (panelW + 8);
        logH = Mth.clamp(this.height - panelY0 - 30, 140, 280);
    }

    private int panelHeight(String cat) {
        return 22 + Modules.inCategory(cat).size() * RH + 6;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String clock(long ms) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(CLOCK);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        layout();
        float a = anim();

        String title = "ANCHOR ADDON";
        Gfx.text(g, this.font, title, (this.width - this.font.width(title)) / 2, 11, Gfx.mulA(Gfx.GOLD, a));
        String sub = "Left-click: toggle    Right-click: module settings    Drag overlays to move them";
        Gfx.text(g, this.font, sub, (this.width - this.font.width(sub)) / 2, 23, Gfx.mulA(Gfx.MUTED, a));

        Module hovered = null;
        for (int ci = 0; ci < Modules.CATEGORIES.length; ci++) {
            String cat = Modules.CATEGORIES[ci];
            List<Module> mods = Modules.inCategory(cat);
            int px = panelX0 + ci * (panelW + 8);
            int ph = panelHeight(cat);
            Gfx.glass(g, px, panelY0, panelW, ph, a);
            g.fill(px + 2, panelY0, px + panelW - 2, panelY0 + 1, Gfx.mulA(Gfx.GOLD, a));
            Gfx.text(g, this.font, cat, px + 8, panelY0 + 7, Gfx.mulA(Gfx.GOLD, a));
            int on = 0;
            for (Module m : mods) if (m.enabled()) on++;
            String cnt = on + "/" + mods.size();
            Gfx.text(g, this.font, cnt, px + panelW - 8 - this.font.width(cnt), panelY0 + 7, Gfx.mulA(Gfx.MUTED, a));
            g.fill(px + 6, panelY0 + 18, px + panelW - 6, panelY0 + 19, Gfx.mulA(Gfx.GOLD, 0.25f * a));

            for (int i = 0; i < mods.size(); i++) {
                Module m = mods.get(i);
                int ry = panelY0 + 22 + i * RH;
                boolean hover = in(mouseX, mouseY, px + 4, ry, panelW - 8, RH - 1) && dragging == null;
                if (hover) hovered = m;
                if (m.enabled()) {
                    Gfx.box(g, px + 4, ry, panelW - 8, RH - 1, Gfx.mulA(0x55E8B923, a));
                    g.fill(px + 4, ry + 2, px + 6, ry + RH - 3, Gfx.mulA(Gfx.GOLD, a));
                } else if (hover) {
                    Gfx.box(g, px + 4, ry, panelW - 8, RH - 1, Gfx.mulA(0x22FFFFFF, a));
                }
                String keyTag = (m.key != null && !m.key.isUnbound()) ? "[" + shortKey(m.keyLabel()) + "]" : "";
                int nameW = panelW - 20 - (keyTag.isEmpty() ? 0 : this.font.width(keyTag) + 4);
                Gfx.text(g, this.font, Gfx.fit(this.font, m.name, nameW), px + 10, ry + 3,
                        Gfx.mulA(m.enabled() ? Gfx.TEXT : Gfx.MUTED, a));
                if (!keyTag.isEmpty()) {
                    Gfx.text(g, this.font, keyTag, px + panelW - 8 - this.font.width(keyTag), ry + 3, Gfx.mulA(Gfx.GOLD_DIM, a));
                }
            }
        }

        drawLog(g, mouseX, mouseY, a);
        Overlays.drawAll(g);

        if (hovered != null) {
            String tip = hovered.name + ": " + hovered.desc;
            int tw = Math.min(this.width - 16, this.font.width(tip) + 14);
            int tx = (this.width - tw) / 2;
            int ty = this.height - 24;
            Gfx.glass(g, tx, ty, tw, 16, 1f);
            Gfx.text(g, this.font, Gfx.fit(this.font, tip, tw - 14), tx + 7, ty + 4, Gfx.TEXT);
        } else {
            String hint = "Right Ctrl or Esc to close";
            Gfx.text(g, this.font, hint, (this.width - this.font.width(hint)) / 2, this.height - 16, Gfx.mulA(Gfx.MUTED, a));
        }
    }

    private static String shortKey(String s) {
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    private void drawLog(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Gfx.glass(g, logX, panelY0, logW, logH, a);
        g.fill(logX + 2, panelY0, logX + logW - 2, panelY0 + 1, Gfx.mulA(Gfx.GOLD, a));
        Gfx.text(g, this.font, "Activity Log", logX + 8, panelY0 + 7, Gfx.mulA(Gfx.GOLD, a));
        int cw = this.font.width("Clear") + 10;
        int cx = logX + logW - 6 - cw;
        boolean hov = in(mouseX, mouseY, cx, panelY0 + 4, cw, 12);
        Gfx.box(g, cx, panelY0 + 4, cw, 12, Gfx.mulA(hov ? 0x66E8B923 : 0x33E8B923, a));
        Gfx.text(g, this.font, "Clear", cx + 5, panelY0 + 7, Gfx.mulA(Gfx.TEXT, a));
        g.fill(logX + 6, panelY0 + 18, logX + logW - 6, panelY0 + 19, Gfx.mulA(Gfx.GOLD, 0.25f * a));

        List<ActivityLog.Entry> all = ActivityLog.snapshot();
        int rows = (logH - 26) / 10;
        int max = Math.max(0, all.size() - rows);
        logScroll = Mth.clamp(logScroll, 0, max);
        int end = all.size() - logScroll;
        int start = Math.max(0, end - rows);
        if (all.isEmpty()) {
            Gfx.text(g, this.font, "Nothing logged yet.", logX + 8, panelY0 + 24, Gfx.mulA(Gfx.MUTED, a));
        }
        int y = panelY0 + 23;
        for (int i = start; i < end; i++) {
            ActivityLog.Entry e = all.get(i);
            int cc = e.level == ActivityLog.Level.INFO ? Gfx.GOLD_DIM : e.level == ActivityLog.Level.WARN ? Gfx.YELLOW : Gfx.RED;
            String t = clock(e.time) + " ";
            String cat = "[" + e.category + "] ";
            int x = logX + 8;
            Gfx.text(g, this.font, t, x, y, Gfx.mulA(Gfx.MUTED, a));
            x += this.font.width(t);
            Gfx.text(g, this.font, cat, x, y, Gfx.mulA(cc, a));
            x += this.font.width(cat);
            Gfx.text(g, this.font, Gfx.fit(this.font, e.text(), logX + logW - 6 - x), x, y, Gfx.mulA(Gfx.TEXT, a));
            y += 10;
        }
        if (logScroll > 0) {
            Gfx.text(g, this.font, "scrolled up " + logScroll + " (wheel)", logX + 8, panelY0 + logH - 11, Gfx.mulA(Gfx.MUTED, a));
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Minecraft 26.3 uses SDL mouse buttons: left = 1, middle = 2, right = 3 (older GLFW numbering: left = 0).
        int btn = event.button();
        boolean left = btn == 0 || btn == 1;
        boolean opensSettings = btn == 2 || btn == 3;
        double mx = event.x(), my = event.y();
        layout();

        if (left) {
            Overlays.Id id = Overlays.hit(mx, my);
            if (id != null) {
                int[] r = Overlays.rect(id, Minecraft.getInstance());
                dragging = id;
                dragOffX = mx - r[0];
                dragOffY = my - r[1];
                return true;
            }
        }

        for (int ci = 0; ci < Modules.CATEGORIES.length; ci++) {
            List<Module> mods = Modules.inCategory(Modules.CATEGORIES[ci]);
            int px = panelX0 + ci * (panelW + 8);
            for (int i = 0; i < mods.size(); i++) {
                int ry = panelY0 + 22 + i * RH;
                if (!in(mx, my, px + 4, ry, panelW - 8, RH - 1)) continue;
                Module m = mods.get(i);
                if (left) {
                    m.toggle();
                    Config.save();
                    ActivityLog.info("Module", m.name + (m.enabled() ? " enabled." : " disabled."));
                } else if (opensSettings) {
                    Minecraft.getInstance().gui.setScreen(new ModuleScreen(this, m));
                }
                return true;
            }
        }

        int cw = this.font.width("Clear") + 10;
        if (left && in(mx, my, logX + logW - 6 - cw, panelY0 + 4, cw, 12)) {
            ActivityLog.clear();
            logScroll = 0;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // Not annotated with @Override on purpose: if a signature differs in some version the menu still works
    // (clicking), and with the right signature dragging and scrolling work too.
    public boolean mouseDragged(MouseButtonEvent event, double dragXd, double dragYd) {
        if (dragging == null) return false;
        Overlays.move(dragging, (int) (event.x() - dragOffX), (int) (event.y() - dragOffY));
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            Config.save();
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (in(mx, my, logX, panelY0, logW, logH)) {
            logScroll += scrollY > 0 ? 3 : -3;
            logScroll = Math.max(0, logScroll);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RCONTROL) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
