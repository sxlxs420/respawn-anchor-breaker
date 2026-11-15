package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Dark, card-based settings screen: category sidebar on the left, toggle/stepper cards on the right. */
public final class ClickGuiScreen extends Screen {
    // palette
    private static final int BORDER = 0xFF2A2A2A;
    private static final int PANEL = 0xF2101010;
    private static final int SIDEBAR = 0xF2141414;
    private static final int CARD = 0xFF1B1B1B;
    private static final int CARD_HOVER = 0xFF232323;
    private static final int TEXT = 0xFFEAEAEA;
    private static final int MUTED = 0xFF8A8A8A;
    private static final int ACCENT = 0xFFC8A02A;
    private static final int SELECTED = 0xFF2A2517;
    private static final int TRACK_OFF = 0xFF3A3A3A;
    private static final int KNOB = 0xFFF2F2F2;
    private static final int BTN = 0xFF2C2C2C;
    private static final int BTN_HOVER = 0xFF3A3A3A;

    private static final int SIDEBAR_W = 104;
    private static final int CARD_H = 34;
    private static final int GAP = 8;

    private static final class Entry {
        final String category, name, desc;
        final BooleanSupplier getB;
        final Consumer<Boolean> setB;
        final IntSupplier getI;
        final IntConsumer setI;
        final int min, max, step;
        final boolean slider;

        Entry(String category, String name, String desc, BooleanSupplier getB, Consumer<Boolean> setB) {
            this.category = category; this.name = name; this.desc = desc;
            this.getB = getB; this.setB = setB;
            this.getI = null; this.setI = null; this.min = 0; this.max = 0; this.step = 1; this.slider = false;
        }

        Entry(String category, String name, String desc, IntSupplier getI, IntConsumer setI, int min, int max) {
            this.category = category; this.name = name; this.desc = desc;
            this.getB = null; this.setB = null;
            this.getI = getI; this.setI = setI; this.min = min; this.max = max; this.step = 1; this.slider = false;
        }

        /** Slider: drag or click along the track; snaps to multiples of step. */
        Entry(String category, String name, String desc, IntSupplier getI, IntConsumer setI, int min, int max, int step) {
            this.category = category; this.name = name; this.desc = desc;
            this.getB = null; this.setB = null;
            this.getI = getI; this.setI = setI; this.min = min; this.max = max; this.step = step; this.slider = true;
        }

        boolean isToggle() { return getB != null; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private String selected;
    private boolean onlyEnabled;
    private Entry dragging;
    private int dragX, dragW;

    // layout (recomputed every frame)
    private int px, py, pw, ph;

    public ClickGuiScreen() {
        super(Component.literal("Respawn Anchor Breaker"));
        Config c = Config.get();
        entries.add(new Entry("Anchors", "Enabled", "Master switch for the mod", () -> c.enabled, v -> c.enabled = v));
        entries.add(new Entry("Anchors", "Clear Snow", "Mine snow covering an anchor", () -> c.clearSnow, v -> c.clearSnow = v));
        entries.add(new Entry("Tools", "Auto Swap", "Switch to your pickaxe slot", () -> c.autoSwap, v -> c.autoSwap = v));
        entries.add(new Entry("Tools", "Pickaxe Slot", "Hotbar slot of your pickaxe", () -> c.pickaxeSlot, v -> c.pickaxeSlot = v, 1, 9));
        entries.add(new Entry("Tools", "Auto Eat", "Eat after each anchor", () -> c.autoEat, v -> c.autoEat = v));
        entries.add(new Entry("Tools", "Food Slot", "Hotbar slot of your food", () -> c.foodSlot, v -> c.foodSlot = v, 1, 9));
        entries.add(new Entry("Tools", "Eat Count", "Pieces to eat per anchor", () -> c.eatCount, v -> c.eatCount = v, 1, 10));
        entries.add(new Entry("Movement", "Auto Pickup", "Walk to dropped anchors", () -> c.autoPickup, v -> c.autoPickup = v));
        entries.add(new Entry("Movement", "Max Drop", "Biggest fall it will walk off", () -> c.maxDropBlocks, v -> c.maxDropBlocks = v, 1, 64));
        entries.add(new Entry("Deposit", "Deposit Anchors", "Store them in an ender chest", () -> c.depositAnchors, v -> c.depositAnchors = v));
        entries.add(new Entry("Deposit", "Deposit At", "Anchors carried before depositing", () -> c.depositThreshold, v -> c.depositThreshold = v, 32, 640, 32));
        entries.add(new Entry("Deposit", "Retrieve Chest", "Mine it back (Silk Touch)", () -> c.retrieveChest, v -> c.retrieveChest = v));
        entries.add(new Entry("Baritone", "Baritone Goto", "Sends #goto when none are left", () -> c.baritoneGoto, v -> c.baritoneGoto = v));

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Entry e : entries) seen.put(e.category, true);
        categories.addAll(seen.keySet());
        selected = categories.get(0);
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

    // ---------------------------------------------------------------- layout helpers

    private void layout() {
        pw = Math.min(480, this.width - 16);
        ph = Math.min(270, this.height - 16);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
    }

    private int contentX() { return px + SIDEBAR_W; }

    private int cardW() { return (pw - SIDEBAR_W - GAP * 3) / 2; }

    private List<Entry> visible() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.category.equals(selected)) continue;
            if (onlyEnabled && !(e.isToggle() && e.getB.getAsBoolean())) continue;
            out.add(e);
        }
        return out;
    }

    private int cardX(int i) { return contentX() + GAP + (i % 2) * (cardW() + GAP); }

    private int cardY(int i) { return py + 40 + (i / 2) * (CARD_H + GAP); }

    private int enabledCount(String category) {
        int n = 0;
        for (Entry e : entries) {
            if (e.category.equals(category) && e.isToggle() && e.getB.getAsBoolean()) n++;
        }
        return n;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String fit(String s, int maxW) {
        if (this.font.width(s) <= maxW) return s;
        while (s.length() > 1 && this.font.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** Rectangle with the four corner pixels cut off (looks rounded at GUI scale). */
    private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, color, false);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        layout();

        // frame, panel, sidebar
        box(g, px - 1, py - 1, pw + 2, ph + 2, BORDER);
        box(g, px, py, pw, ph, PANEL);
        g.fill(px, py + 1, px + SIDEBAR_W, py + ph - 1, SIDEBAR);
        g.fill(px + 1, py, px + SIDEBAR_W, py + ph, SIDEBAR);
        g.fill(px + SIDEBAR_W, py, px + SIDEBAR_W + 1, py + ph, BORDER);

        // sidebar: title, categories with enabled-count, close hint
        text(g, "Anchor Breaker", px + 10, py + 12, ACCENT);
        text(g, "Modules", px + 10, py + 30, MUTED);
        for (int i = 0; i < categories.size(); i++) {
            String cat = categories.get(i);
            int ry = py + 44 + i * 22;
            boolean sel = cat.equals(selected);
            boolean hover = in(mouseX, mouseY, px + 6, ry, SIDEBAR_W - 12, 20);
            if (sel) {
                box(g, px + 6, ry, SIDEBAR_W - 12, 20, SELECTED);
                g.fill(px + 6, ry + 4, px + 8, ry + 16, ACCENT);
            } else if (hover) {
                box(g, px + 6, ry, SIDEBAR_W - 12, 20, CARD);
            }
            text(g, cat, px + 14, ry + 6, sel ? ACCENT : TEXT);
            int count = enabledCount(cat);
            String n = Integer.toString(count);
            text(g, n, px + SIDEBAR_W - 12 - this.font.width(n), ry + 6, count > 0 ? ACCENT : MUTED);
        }
        text(g, "RCtrl / Esc", px + 10, py + ph - 24, MUTED);
        text(g, "to close", px + 10, py + ph - 14, MUTED);

        // header: category title, module count, divider, All / On pills
        List<Entry> list = visible();
        int hx = contentX() + GAP + 2;
        text(g, selected, hx, py + 14, TEXT);
        text(g, list.size() + (list.size() == 1 ? " module" : " modules"),
                hx + this.font.width(selected) + 8, py + 14, MUTED);
        g.fill(contentX() + GAP, py + 30, px + pw - GAP, py + 31, BORDER);

        int pillOnX = px + pw - GAP - 28;
        int pillAllX = pillOnX - 32;
        box(g, pillAllX, py + 9, 30, 14, onlyEnabled ? CARD : SELECTED);
        box(g, pillOnX, py + 9, 28, 14, onlyEnabled ? SELECTED : CARD);
        text(g, "All", pillAllX + 9, py + 13, onlyEnabled ? MUTED : ACCENT);
        text(g, "On", pillOnX + 9, py + 13, onlyEnabled ? ACCENT : MUTED);

        // cards
        int cw = cardW();
        if (list.isEmpty()) {
            String msg = "Nothing enabled here";
            text(g, msg, contentX() + (pw - SIDEBAR_W - this.font.width(msg)) / 2, py + 60, MUTED);
        }
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            int cx = cardX(i), cy = cardY(i);
            box(g, cx, cy, cw, CARD_H, in(mouseX, mouseY, cx, cy, cw, CARD_H) ? CARD_HOVER : CARD);

            if (e.slider) {
                int v = e.getI.getAsInt();
                String vs = v + " (" + stackLabel(v) + ")";
                text(g, e.name, cx + 8, cy + 6, TEXT);
                text(g, vs, cx + cw - 8 - this.font.width(vs), cy + 6, ACCENT);
                int tx = cx + 8, tw = cw - 16, ty = cy + 22;
                box(g, tx, ty, tw, 4, TRACK_OFF);
                int fw = (int) ((long) (v - e.min) * tw / Math.max(1, e.max - e.min));
                if (fw > 0) box(g, tx, ty, Math.max(2, fw), 4, ACCENT);
                box(g, tx + fw - 3, ty - 3, 6, 10, KNOB);
                continue;
            }

            int rightW = e.isToggle() ? 42 : 56;
            int textW = cw - 16 - rightW;
            text(g, fit(e.name, textW), cx + 8, cy + 7, TEXT);
            text(g, fit(e.desc, textW), cx + 8, cy + 20, MUTED);

            if (e.isToggle()) {
                boolean on = e.getB.getAsBoolean();
                if (on) g.fill(cx, cy + 5, cx + 2, cy + CARD_H - 5, ACCENT);
                int tx = cx + cw - 8 - 22, ty = cy + 12;
                box(g, tx, ty, 22, 10, on ? ACCENT : TRACK_OFF);
                int kx = on ? tx + 13 : tx + 1;
                box(g, kx, ty + 1, 8, 8, KNOB);
                // small grip dots left of the switch
                for (int a2 = 0; a2 < 2; a2++) {
                    for (int b2 = 0; b2 < 3; b2++) {
                        g.fill(tx - 11 + a2 * 3, ty - 1 + b2 * 3, tx - 10 + a2 * 3, ty + b2 * 3, MUTED);
                    }
                }
            } else {
                int by = cy + 11;
                int plusX = cx + cw - 8 - 12;
                int minusX = plusX - 12 - 26;
                box(g, minusX, by, 12, 12, in(mouseX, mouseY, minusX, by, 12, 12) ? BTN_HOVER : BTN);
                box(g, plusX, by, 12, 12, in(mouseX, mouseY, plusX, by, 12, 12) ? BTN_HOVER : BTN);
                text(g, "-", minusX + 4, by + 2, TEXT);
                text(g, "+", plusX + 3, by + 2, TEXT);
                String v = Integer.toString(e.getI.getAsInt());
                text(g, v, minusX + 12 + (26 - this.font.width(v)) / 2, by + 2, ACCENT);
            }
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Minecraft 26.3 uses SDL mouse buttons: left = 1 (older GLFW numbering used 0).
        int btn = event.button();
        if (btn != 0 && btn != 1) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        layout();

        // categories
        for (int i = 0; i < categories.size(); i++) {
            int ry = py + 44 + i * 22;
            if (in(mx, my, px + 6, ry, SIDEBAR_W - 12, 20)) {
                selected = categories.get(i);
                return true;
            }
        }

        // All / On pills
        int pillOnX = px + pw - GAP - 28;
        int pillAllX = pillOnX - 32;
        if (in(mx, my, pillAllX, py + 9, 30, 14)) { onlyEnabled = false; return true; }
        if (in(mx, my, pillOnX, py + 9, 28, 14)) { onlyEnabled = true; return true; }

        // cards
        List<Entry> list = visible();
        int cw = cardW();
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            int cx = cardX(i), cy = cardY(i);
            if (!in(mx, my, cx, cy, cw, CARD_H)) continue;
            if (e.slider) {
                dragging = e;
                dragX = cx + 8;
                dragW = cw - 16;
                setSlider(e, mx);
                return true;
            }
            if (e.isToggle()) {
                e.setB.accept(!e.getB.getAsBoolean());
            } else {
                int by = cy + 11;
                int plusX = cx + cw - 8 - 12;
                int minusX = plusX - 12 - 26;
                int v = e.getI.getAsInt();
                if (in(mx, my, minusX, by, 12, 12)) e.setI.accept(Math.max(e.min, v - 1));
                else if (in(mx, my, plusX, by, 12, 12)) e.setI.accept(Math.min(e.max, v + 1));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 32 -> "half a stack", 64 -> "1 stack", 96 -> "1.5 stacks" (anchors stack to 64). */
    private static String stackLabel(int count) {
        if (count == 32) return "half a stack";
        double stacks = count / 64.0;
        String n = (count % 64 == 0) ? Integer.toString(count / 64) : Double.toString(stacks);
        return n + (count == 64 ? " stack" : " stacks");
    }

    private void setSlider(Entry e, double mx) {
        double t = Mth.clamp((mx - dragX) / Math.max(1, dragW), 0.0, 1.0);
        double raw = e.min + t * (e.max - e.min);
        int snapped = e.min + (int) Math.round((raw - e.min) / e.step) * e.step;
        e.setI.accept(Mth.clamp(snapped, e.min, e.max));
    }

    // Not annotated with @Override on purpose: if a signature differs in this version the slider
    // still works by clicking; with the right signature, dragging works too.
    public boolean mouseDragged(MouseButtonEvent event, double dragXd, double dragYd) {
        if (dragging == null) return false;
        setSlider(dragging, event.x());
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
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
