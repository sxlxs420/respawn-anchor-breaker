package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The HUD overlays: credit card, market, farm stats, keybinds list and log. All draggable from the menu. */
public final class Overlays {
    public enum Id { CREDIT, MARKET, STATS, KEYS, LOG }

    private static final int PAD = 6;
    private static final int LH = 10;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    static final class Seg {
        final String text;
        final int color;
        final int arrow; // 0 none, 1 up, -1 down, 2 flat

        Seg(String text, int color, int arrow) {
            this.text = text;
            this.color = color;
            this.arrow = arrow;
        }
    }

    static final class Line {
        final List<Seg> segs = new ArrayList<>();
        boolean divider;

        Line add(String t, int c) {
            segs.add(new Seg(t, c, 0));
            return this;
        }

        Line arrow(int dir, int c) {
            segs.add(new Seg("", c, dir > 0 ? 1 : dir < 0 ? -1 : 2));
            return this;
        }

        static Line divider() {
            Line l = new Line();
            l.divider = true;
            return l;
        }
    }

    static final class Panel {
        String title = "";
        String badge = "";
        int badgeColor = Gfx.MUTED;
        final List<Line> lines = new ArrayList<>();
        int w;
        int h;
    }

    private Overlays() { }

    public static boolean visible(Id id) {
        Config c = Config.get();
        return switch (id) {
            case CREDIT -> c.showOverlay;
            case MARKET -> c.marketOverlay;
            case STATS -> c.statsOverlay;
            case KEYS -> c.keysOverlay && Modules.anyBound();
            case LOG -> c.logOverlay;
        };
    }

    // ------------------------------------------------------------------ content

    private static Panel build(Id id) {
        return switch (id) {
            case CREDIT -> credit();
            case MARKET -> market();
            case STATS -> stats();
            case KEYS -> keys();
            case LOG -> log();
        };
    }

    private static Panel credit() {
        Config c = Config.get();
        Panel p = new Panel();
        p.title = "Anchor Addon";
        p.badge = c.enabled ? "ON" : "OFF";
        p.badgeColor = c.enabled ? Gfx.GREEN : Gfx.RED;
        p.lines.add(new Line().add("GitHub ", Gfx.MUTED).add("sxlxs420", Gfx.TEXT));
        p.lines.add(new Line().add("Temple Client + Baritone", Gfx.TEXT));
        p.lines.add(new Line().add("temple.nz  ", Gfx.TEXT).add("code ", Gfx.MUTED).add("wks", Gfx.GOLD).add("  15% off", Gfx.MUTED));
        p.lines.add(new Line().add("discord.gg/templ", Gfx.GOLD));
        return p;
    }

    private static String money(double v) {
        return String.format(Locale.US, "$%,.0f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%+.2f%%", v);
    }

    private static int trendColor(double v) {
        return v > 0.05 ? Gfx.GREEN : v < -0.05 ? Gfx.RED : Gfx.MUTED;
    }

    private static int trendDir(double v) {
        return v > 0.05 ? 1 : v < -0.05 ? -1 : 0;
    }

    private static Panel market() {
        Config c = Config.get();
        Panel p = new Panel();
        p.title = "Anchor Market";
        Market.Status st = Market.status();
        switch (st) {
            case LIVE -> { p.badge = "LIVE"; p.badgeColor = Gfx.GREEN; }
            case STALE -> { p.badge = "CACHED"; p.badgeColor = Gfx.YELLOW; }
            case OFFLINE -> { p.badge = "OFFLINE"; p.badgeColor = Gfx.RED; }
            default -> { p.badge = "NO DATA"; p.badgeColor = Gfx.MUTED; }
        }
        Market.Snapshot s = Market.last();
        if (s == null) {
            p.lines.add(new Line().add("No price data yet", Gfx.MUTED));
            String err = Market.error();
            p.lines.add(new Line().add(err != null ? "Last error: " + err : "Waiting for the first update...",
                    err != null ? Gfx.RED : Gfx.MUTED));
            p.lines.add(new Line().add("Data: donut.auction", Gfx.MUTED));
            return p;
        }
        Double h1 = Market.changePct(3600);
        double[] sess = Market.sinceStart();
        Double headline = h1 != null ? h1 : (sess != null ? Double.valueOf(sess[0]) : null);
        Line price = new Line().add(money(s.price), Gfx.TEXT);
        if (headline != null) {
            price.add("  ", Gfx.TEXT).arrow(trendDir(headline), trendColor(headline)).add(" " + pct(headline), trendColor(headline));
        }
        p.lines.add(price);
        if (c.marketShowChanges) {
            p.lines.add(changeLine("5 min", Market.changePct(300)));
            p.lines.add(changeLine("1 hour", h1));
            p.lines.add(changeLine("24 hours", Market.changePct(86400)));
            if (h1 == null && sess != null) {
                p.lines.add(new Line().add("Since start ", Gfx.MUTED).add(pct(sess[0]), trendColor(sess[0]))
                        .add(String.format(Locale.US, " (%.0f min)", sess[1]), Gfx.MUTED));
            } else if (h1 == null) {
                p.lines.add(new Line().add("Collecting history... " + Market.samples() + " samples", Gfx.MUTED));
            }
        }
        if (c.marketShowRange) {
            double[] r = Market.range24h();
            if (r != null) {
                p.lines.add(new Line().add("24h low ", Gfx.MUTED).add(money(r[0]), Gfx.TEXT));
                p.lines.add(new Line().add("24h high ", Gfx.MUTED).add(money(r[1]), Gfx.TEXT));
                double width = r[1] - r[0];
                if (width > s.price * 0.01) {
                    double pos = (s.price - r[0]) / width;
                    if (pos >= 0.85) p.lines.add(new Line().add("Near 24h high: good time to sell", Gfx.GREEN));
                    else if (pos <= 0.15) p.lines.add(new Line().add("Near 24h low: good time to buy", Gfx.GOLD));
                    else p.lines.add(new Line().add(String.format(Locale.US, "In 24h range: %.0f%%", pos * 100), Gfx.MUTED));
                }
            }
        }
        if (c.marketShowVolume && (s.volume != null || s.sales != null)) {
            Line v = new Line();
            if (s.volume != null) v.add("Volume " + String.format(Locale.US, "%,.0f", s.volume) + "  ", Gfx.TEXT);
            if (s.sales != null) v.add("Sales " + String.format(Locale.US, "%,.0f", s.sales), Gfx.TEXT);
            p.lines.add(v);
        }
        p.lines.add(Line.divider());
        long age = (System.currentTimeMillis() - s.fetchedAt) / 1000;
        p.lines.add(new Line().add("Updated " + (age < 90 ? age + "s" : (age / 60) + "m") + " ago", Gfx.MUTED));
        if (Market.error() != null) {
            p.lines.add(new Line().add("Using cached data: " + Market.error(), Gfx.YELLOW));
        }
        p.lines.add(new Line().add("Data: donut.auction", Gfx.MUTED));
        return p;
    }

    private static Line changeLine(String label, Double v) {
        Line l = new Line().add(String.format(Locale.US, "%-9s", label), Gfx.MUTED);
        if (v == null) return l.add("n/a", Gfx.MUTED);
        return l.arrow(trendDir(v), trendColor(v)).add(" " + pct(v), trendColor(v));
    }

    private static Panel stats() {
        Panel p = new Panel();
        p.title = "Farm Stats";
        p.badge = Stats.uptime();
        p.badgeColor = Gfx.MUTED;
        p.lines.add(new Line().add("Now ", Gfx.MUTED).add(AnchorController.activity, Gfx.GOLD));
        Double avg = Stats.avgPerHour();
        p.lines.add(new Line().add("Mined ", Gfx.MUTED).add(Integer.toString(Stats.session()), Gfx.TEXT)
                .add("   Avg ", Gfx.MUTED).add(avg == null ? "..." : String.format(Locale.US, "%.0f/h", avg), Gfx.TEXT));
        Double r10 = Stats.rate(10);
        Double r60 = Stats.rate(60);
        p.lines.add(new Line().add("10 min ", Gfx.MUTED).add(r10 == null ? "..." : String.format(Locale.US, "%.0f/h", r10), Gfx.TEXT)
                .add("   1 h ", Gfx.MUTED).add(r60 == null ? "..." : String.format(Locale.US, "%.0f/h", r60), Gfx.TEXT));
        p.lines.add(new Line().add("Carrying ", Gfx.MUTED).add(Integer.toString(AnchorController.invAnchors), Gfx.TEXT)
                .add("   Stored ", Gfx.MUTED).add(Long.toString(Stats.deposited()), Gfx.TEXT));
        p.lines.add(new Line().add("All time ", Gfx.MUTED).add(String.format(Locale.US, "%,d", Config.get().lifetimeMined), Gfx.TEXT));
        return p;
    }

    private static Panel keys() {
        Panel p = new Panel();
        p.title = "Keybinds";
        for (Module m : Modules.ALL) {
            if (m.key == null || m.key.isUnbound()) continue;
            p.lines.add(new Line().add(m.name + "  ", m.enabled() ? Gfx.TEXT : Gfx.MUTED).add(m.keyLabel(), Gfx.GOLD));
        }
        return p;
    }

    private static Panel log() {
        Panel p = new Panel();
        p.title = "Activity";
        List<ActivityLog.Entry> all = ActivityLog.snapshot();
        int n = Config.get().logLines;
        int from = Math.max(0, all.size() - n);
        if (all.isEmpty()) p.lines.add(new Line().add("Nothing yet", Gfx.MUTED));
        for (int i = from; i < all.size(); i++) {
            ActivityLog.Entry e = all.get(i);
            int cc = e.level == ActivityLog.Level.INFO ? Gfx.GOLD_DIM : e.level == ActivityLog.Level.WARN ? Gfx.YELLOW : Gfx.RED;
            String t = LocalTime.ofSecondOfDay((e.time / 1000 + java.util.TimeZone.getDefault().getOffset(e.time) / 1000) % 86400)
                    .format(CLOCK);
            String msg = e.text();
            if (msg.length() > 46) msg = msg.substring(0, 46) + "..";
            p.lines.add(new Line().add(t + " ", Gfx.MUTED).add("[" + e.category + "] ", cc).add(msg, Gfx.TEXT));
        }
        return p;
    }

    // ------------------------------------------------------------------ layout and drawing

    private static void measure(Font f, Panel p) {
        int w = f.width(p.title) + (p.badge.isEmpty() ? 0 : f.width(p.badge) + 18);
        int h = PAD + 16;
        for (Line l : p.lines) {
            if (l.divider) {
                h += 6;
                continue;
            }
            int lw = 0;
            for (Seg s : l.segs) lw += s.arrow != 0 ? 7 : f.width(s.text);
            w = Math.max(w, lw);
            h += LH;
        }
        p.w = w + PAD * 2;
        p.h = h + PAD - 3;
    }

    private static int[] posOf(Id id) {
        Config c = Config.get();
        return switch (id) {
            case CREDIT -> new int[]{c.overlayX, c.overlayY};
            case MARKET -> new int[]{c.marketX, c.marketY};
            case STATS -> new int[]{c.statsX, c.statsY};
            case KEYS -> new int[]{c.keysX, c.keysY};
            case LOG -> new int[]{c.logX, c.logY};
        };
    }

    /** {x, y, w, h} on screen, clamped to the window; null when the overlay is hidden. */
    public static int[] rect(Id id, Minecraft mc) {
        if (!visible(id)) return null;
        Panel p = build(id);
        measure(mc.font, p);
        int[] pos = posOf(id);
        int x = Mth.clamp(pos[0], 0, Math.max(0, mc.getWindow().getGuiScaledWidth() - p.w));
        int y = Mth.clamp(pos[1], 0, Math.max(0, mc.getWindow().getGuiScaledHeight() - p.h));
        return new int[]{x, y, p.w, p.h};
    }

    public static void move(Id id, int x, int y) {
        Config c = Config.get();
        switch (id) {
            case CREDIT -> { c.overlayX = x; c.overlayY = y; }
            case MARKET -> { c.marketX = x; c.marketY = y; }
            case STATS -> { c.statsX = x; c.statsY = y; }
            case KEYS -> { c.keysX = x; c.keysY = y; }
            case LOG -> { c.logX = x; c.logY = y; }
        }
    }

    /** Topmost overlay under the cursor, or null. */
    public static Id hit(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        Id[] ids = Id.values();
        for (int i = ids.length - 1; i >= 0; i--) {
            int[] r = rect(ids[i], mc);
            if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return ids[i];
        }
        return null;
    }

    public static void drawAll(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        for (Id id : Id.values()) {
            if (!visible(id)) continue;
            Panel p = build(id);
            Font f = mc.font;
            measure(f, p);
            int[] pos = posOf(id);
            int x = Mth.clamp(pos[0], 0, Math.max(0, mc.getWindow().getGuiScaledWidth() - p.w));
            int y = Mth.clamp(pos[1], 0, Math.max(0, mc.getWindow().getGuiScaledHeight() - p.h));
            drawPanel(g, f, p, x, y);
        }
    }

    private static void drawPanel(GuiGraphicsExtractor g, Font f, Panel p, int x, int y) {
        Gfx.glass(g, x, y, p.w, p.h, 1f);
        g.fill(x + 2, y, x + p.w - 2, y + 1, Gfx.GOLD);
        Gfx.text(g, f, p.title, x + PAD, y + PAD, Gfx.GOLD);
        if (!p.badge.isEmpty()) {
            int bw = f.width(p.badge);
            Gfx.text(g, f, p.badge, x + p.w - PAD - bw, y + PAD, p.badgeColor);
            g.fill(x + p.w - PAD - bw - 7, y + PAD + 2, x + p.w - PAD - bw - 3, y + PAD + 6, p.badgeColor);
        }
        g.fill(x + PAD, y + PAD + 11, x + p.w - PAD, y + PAD + 12, Gfx.mulA(Gfx.GOLD, 0.25f));
        int cy = y + PAD + 16;
        for (Line l : p.lines) {
            if (l.divider) {
                g.fill(x + PAD, cy + 2, x + p.w - PAD, cy + 3, 0x22FFFFFF);
                cy += 6;
                continue;
            }
            int cx = x + PAD;
            for (Seg s : l.segs) {
                if (s.arrow != 0) {
                    Gfx.arrow(g, cx, cy + 2, s.arrow == 2 ? 0 : s.arrow, s.color);
                    cx += 7;
                } else {
                    Gfx.text(g, f, s.text, cx, cy, s.color);
                    cx += f.width(s.text);
                }
            }
            cy += LH;
        }
    }
}
