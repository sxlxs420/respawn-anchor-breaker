package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/** Settings page for one module: enable switch, its sliders / toggles / text fields, and its keybind. */
public final class ModuleScreen extends Screen {
    private static final int ROW = 20;
    private static final int PW = 320;
    private static final int CTRL_W = 168;

    private final Screen parent;
    private final Module module;
    private final long openedAt = System.currentTimeMillis();
    private int px, py, ph;

    private int dragRow = -1;
    private int editRow = -1;
    private String editBuf = "";
    private boolean listeningKey;

    public ModuleScreen(Screen parent, Module module) {
        super(Component.literal(module.name));
        this.parent = parent;
        this.module = module;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void removed() {
        Config.save();
        super.removed();
    }

    private int rows() {
        return module.settings.size() + 2; // enabled + settings + keybind
    }

    private void layout() {
        ph = 44 + rows() * ROW + 10;
        px = (this.width - PW) / 2;
        py = Math.max(6, (this.height - ph) / 2);
    }

    private int rowY(int i) {
        return py + 40 + i * ROW;
    }

    private int ctrlX() {
        return px + PW - 12 - CTRL_W;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String fmt(double v, double step) {
        return step >= 1 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private String valueText(Module.Setting s) {
        return switch (s.type) {
            case INT -> Integer.toString(s.getI.getAsInt());
            case DOUBLE -> fmt(s.getD.getAsDouble(), s.dstep);
            default -> "";
        };
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        layout();
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        float a = t * t * (3 - 2 * t);

        Gfx.glass(g, px, py, PW, ph, a);
        g.fill(px + 2, py, px + PW - 2, py + 1, Gfx.mulA(Gfx.GOLD, a));
        Gfx.text(g, this.font, module.name, px + 12, py + 9, Gfx.mulA(Gfx.GOLD, a));
        Gfx.text(g, this.font, Gfx.fit(this.font, module.desc, PW - 24), px + 12, py + 21, Gfx.mulA(Gfx.MUTED, a));
        g.fill(px + 10, py + 34, px + PW - 10, py + 35, Gfx.mulA(Gfx.GOLD, 0.25f * a));

        // row 0: enabled
        drawRow(g, 0, mouseX, mouseY, a);
        label(g, 0, "Enabled", "Turn this module on or off.", a);
        drawSwitch(g, ctrlX() + CTRL_W - 24, rowY(0) + 4, module.enabled(), a);

        for (int i = 0; i < module.settings.size(); i++) {
            Module.Setting s = module.settings.get(i);
            int row = i + 1;
            int y = rowY(row);
            drawRow(g, row, mouseX, mouseY, a);
            label(g, row, s.name, s.desc, a);
            int cx = ctrlX();
            switch (s.type) {
                case TOGGLE -> drawSwitch(g, cx + CTRL_W - 24, y + 4, s.getB.getAsBoolean(), a);
                case INT, DOUBLE -> drawSlider(g, s, row, cx, y, a);
                case TEXT -> {
                    boolean ed = editRow == row;
                    Gfx.box(g, cx, y + 2, CTRL_W, 14, Gfx.mulA(ed ? 0x66E8B923 : 0x44000000, a));
                    String shown = ed ? editBuf + ((System.currentTimeMillis() / 400) % 2 == 0 ? "_" : "") : s.getS.get();
                    Gfx.text(g, this.font, Gfx.fit(this.font, shown, CTRL_W - 8), cx + 4, y + 5, Gfx.mulA(Gfx.TEXT, a));
                }
                case BUTTON -> {
                    boolean hov = in(mouseX, mouseY, cx + CTRL_W - 70, y + 2, 70, 14);
                    Gfx.box(g, cx + CTRL_W - 70, y + 2, 70, 14, Gfx.mulA(hov ? 0x88E8B923 : 0x44E8B923, a));
                    Gfx.text(g, this.font, "Run", cx + CTRL_W - 70 + (70 - this.font.width("Run")) / 2, y + 5, Gfx.mulA(Gfx.TEXT, a));
                }
            }
        }

        // last row: keybind
        int kr = rows() - 1;
        drawRow(g, kr, mouseX, mouseY, a);
        label(g, kr, "Keybind", "Click, then press a key. Delete clears it.", a);
        int ky = rowY(kr);
        int cx = ctrlX();
        Gfx.box(g, cx + CTRL_W - 110, ky + 2, 110, 14, Gfx.mulA(listeningKey ? 0x88E8B923 : 0x44E8B923, a));
        String kt = listeningKey ? "Press a key..." : module.keyLabel();
        Gfx.text(g, this.font, kt, cx + CTRL_W - 110 + (110 - this.font.width(kt)) / 2, ky + 5, Gfx.mulA(Gfx.TEXT, a));

        String hint = editRow >= 0 ? "Click outside the box to apply, Esc to cancel" : "Esc to go back";
        Gfx.text(g, this.font, hint, px + 12, py + ph - 13, Gfx.mulA(Gfx.MUTED, a));
    }

    private void drawRow(GuiGraphicsExtractor g, int row, int mx, int my, float a) {
        if (in(mx, my, px + 6, rowY(row), PW - 12, ROW - 2)) {
            Gfx.box(g, px + 6, rowY(row), PW - 12, ROW - 2, Gfx.mulA(0x14FFFFFF, a));
        }
    }

    private void label(GuiGraphicsExtractor g, int row, String name, String desc, float a) {
        int y = rowY(row);
        Gfx.text(g, this.font, name, px + 12, y + 2, Gfx.mulA(Gfx.TEXT, a));
        Gfx.text(g, this.font, Gfx.fit(this.font, desc, PW - CTRL_W - 36), px + 12, y + 11, Gfx.mulA(Gfx.MUTED, a));
    }

    private void drawSwitch(GuiGraphicsExtractor g, int x, int y, boolean on, float a) {
        Gfx.box(g, x, y, 24, 11, Gfx.mulA(on ? Gfx.GOLD : 0xFF3A3A3A, a));
        Gfx.box(g, on ? x + 14 : x + 2, y + 2, 8, 7, Gfx.mulA(0xFFF6F1E3, a));
    }

    private double fraction(Module.Setting s) {
        if (s.type == Module.Type.INT) return (double) (s.getI.getAsInt() - s.imin) / Math.max(1, s.imax - s.imin);
        return (s.getD.getAsDouble() - s.dmin) / Math.max(1e-9, s.dmax - s.dmin);
    }

    private void drawSlider(GuiGraphicsExtractor g, Module.Setting s, int row, int cx, int y, float a) {
        int trackW = CTRL_W - 52;
        int fw = (int) (Mth.clamp(fraction(s), 0.0, 1.0) * trackW);
        Gfx.box(g, cx, y + 7, trackW, 4, Gfx.mulA(0xFF3A3A3A, a));
        if (fw > 0) Gfx.box(g, cx, y + 7, Math.max(3, fw), 4, Gfx.mulA(Gfx.GOLD, a));
        Gfx.box(g, cx + fw - 3, y + 4, 6, 10, Gfx.mulA(0xFFF6F1E3, a));
        boolean ed = editRow == row;
        int bx = cx + trackW + 6;
        Gfx.box(g, bx, y + 2, 46, 14, Gfx.mulA(ed ? 0x66E8B923 : 0x44000000, a));
        String shown = ed ? editBuf + "_" : valueText(s);
        Gfx.text(g, this.font, Gfx.fit(this.font, shown, 40), bx + 4, y + 5, Gfx.mulA(Gfx.GOLD, a));
    }

    // ---------------------------------------------------------------- input

    private void setSliderFromMouse(Module.Setting s, double mx, int cx) {
        int trackW = CTRL_W - 52;
        double f = Mth.clamp((mx - cx) / trackW, 0.0, 1.0);
        if (s.type == Module.Type.INT) {
            double raw = s.imin + f * (s.imax - s.imin);
            int v = s.imin + (int) Math.round((raw - s.imin) / s.istep) * s.istep;
            s.setI.accept(Mth.clamp(v, s.imin, s.imax));
        } else {
            double raw = s.dmin + f * (s.dmax - s.dmin);
            double v = s.dmin + Math.round((raw - s.dmin) / s.dstep) * s.dstep;
            s.setD.accept(Math.round(Mth.clamp(v, s.dmin, s.dmax) * 100.0) / 100.0);
        }
    }

    private void commitEdit() {
        if (editRow < 1 || editRow > module.settings.size()) {
            editRow = -1;
            return;
        }
        Module.Setting s = module.settings.get(editRow - 1);
        try {
            switch (s.type) {
                case TEXT -> s.setS.accept(editBuf.trim());
                case INT -> s.setI.accept(Mth.clamp((int) Math.round(Double.parseDouble(editBuf)), s.imin, s.imax));
                case DOUBLE -> s.setD.accept(Mth.clamp(Double.parseDouble(editBuf), s.dmin, s.dmax));
                default -> { }
            }
        } catch (NumberFormatException ignored) {
            // leave the old value
        }
        editRow = -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int btn = event.button();
        if (btn != 0 && btn != 1) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        layout();
        if (editRow >= 0) commitEdit(); // clicking anywhere applies the pending edit first
        listeningKey = false;

        int cx = ctrlX();
        if (in(mx, my, px + 6, rowY(0), PW - 12, ROW - 2)) {
            module.toggle();
            return true;
        }
        for (int i = 0; i < module.settings.size(); i++) {
            int row = i + 1;
            int y = rowY(row);
            if (!in(mx, my, px + 6, y, PW - 12, ROW - 2)) continue;
            Module.Setting s = module.settings.get(i);
            switch (s.type) {
                case TOGGLE -> s.setB.accept(!s.getB.getAsBoolean());
                case INT, DOUBLE -> {
                    int trackW = CTRL_W - 52;
                    if (in(mx, my, cx + trackW + 6, y + 2, 46, 14)) {
                        editRow = row;
                        editBuf = valueText(s);
                    } else if (in(mx, my, cx - 4, y, trackW + 8, ROW - 2)) {
                        dragRow = row;
                        setSliderFromMouse(s, mx, cx);
                    }
                }
                case TEXT -> {
                    if (in(mx, my, cx, y + 2, CTRL_W, 14)) {
                        editRow = row;
                        editBuf = s.getS.get();
                    }
                }
                case BUTTON -> {
                    if (in(mx, my, cx + CTRL_W - 70, y + 2, 70, 14)) s.action.run();
                }
            }
            return true;
        }
        int kr = rows() - 1;
        if (in(mx, my, cx + CTRL_W - 110, rowY(kr) + 2, 110, 14)) {
            listeningKey = true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // No @Override on purpose (see ClickGuiScreen).
    public boolean mouseDragged(MouseButtonEvent event, double dragXd, double dragYd) {
        if (dragRow < 1 || dragRow > module.settings.size()) return false;
        setSliderFromMouse(module.settings.get(dragRow - 1), event.x(), ctrlX());
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        dragRow = -1;
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (editRow < 1 || editRow > module.settings.size()) return false;
        Module.Setting s = module.settings.get(editRow - 1);
        int cp = event.codepoint();
        if (cp < 32 || cp == 127 || editBuf.length() >= 80) return true;
        if (s.type != Module.Type.TEXT && !(Character.isDigit(cp) || cp == '.' || cp == '-')) return true;
        editBuf = editBuf + new String(Character.toChars(cp));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (listeningKey) {
            listeningKey = false;
            if (key == InputConstants.KEY_ESCAPE) return true;
            if (module.key != null) {
                module.key.setKey(key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE
                        ? InputConstants.UNKNOWN
                        : InputConstants.Type.KEYBOARD.getOrCreate(key));
                net.minecraft.client.KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
                ActivityLog.info("Module", module.name + " keybind set to " + module.keyLabel() + ".");
            }
            return true;
        }
        if (editRow >= 0) {
            if (key == InputConstants.KEY_ESCAPE) {
                editRow = -1; // cancel
            } else if (key == InputConstants.KEY_BACKSPACE && !editBuf.isEmpty()) {
                editBuf = editBuf.substring(0, editBuf.length() - 1);
            }
            return true;
        }
        return super.keyPressed(event);
    }
}
