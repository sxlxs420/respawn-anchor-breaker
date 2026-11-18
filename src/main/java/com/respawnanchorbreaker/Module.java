package com.respawnanchorbreaker;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** One toggleable module with its own settings page (right-click it in the menu) and an optional keybind. */
public final class Module {
    public enum Type { TOGGLE, INT, DOUBLE, TEXT, BUTTON }

    public static final class Setting {
        public final Type type;
        public final String name;
        public final String desc;
        public BooleanSupplier getB;
        public Consumer<Boolean> setB;
        public IntSupplier getI;
        public IntConsumer setI;
        public int imin, imax, istep = 1;
        public DoubleSupplier getD;
        public DoubleConsumer setD;
        public double dmin, dmax, dstep = 0.1;
        public Supplier<String> getS;
        public Consumer<String> setS;
        public Runnable action;

        Setting(Type type, String name, String desc) {
            this.type = type;
            this.name = name;
            this.desc = desc;
        }
    }

    public final String id;
    public final String name;
    public final String desc;
    public final String category;
    private final BooleanSupplier enabledGet;
    private final Consumer<Boolean> enabledSet;
    public final List<Setting> settings = new ArrayList<>();
    public KeyMapping key;

    public Module(String id, String name, String desc, String category, BooleanSupplier get, Consumer<Boolean> set) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.category = category;
        this.enabledGet = get;
        this.enabledSet = set;
    }

    public boolean enabled() { return enabledGet.getAsBoolean(); }

    public void setEnabled(boolean v) { enabledSet.accept(v); }

    public void toggle() { setEnabled(!enabled()); }

    public Module toggle(String n, String d, BooleanSupplier g, Consumer<Boolean> s) {
        Setting st = new Setting(Type.TOGGLE, n, d);
        st.getB = g;
        st.setB = s;
        settings.add(st);
        return this;
    }

    public Module integer(String n, String d, IntSupplier g, IntConsumer s, int min, int max, int step) {
        Setting st = new Setting(Type.INT, n, d);
        st.getI = g;
        st.setI = s;
        st.imin = min;
        st.imax = max;
        st.istep = step;
        settings.add(st);
        return this;
    }

    public Module decimal(String n, String d, DoubleSupplier g, DoubleConsumer s, double min, double max, double step) {
        Setting st = new Setting(Type.DOUBLE, n, d);
        st.getD = g;
        st.setD = s;
        st.dmin = min;
        st.dmax = max;
        st.dstep = step;
        settings.add(st);
        return this;
    }

    public Module text(String n, String d, Supplier<String> g, Consumer<String> s) {
        Setting st = new Setting(Type.TEXT, n, d);
        st.getS = g;
        st.setS = s;
        settings.add(st);
        return this;
    }

    public Module button(String n, String d, Runnable r) {
        Setting st = new Setting(Type.BUTTON, n, d);
        st.action = r;
        settings.add(st);
        return this;
    }

    /** True only for a real key (an old bad "-1" key from a previous build counts as unbound). */
    public boolean bound() {
        if (key == null || key.isUnbound()) return false;
        String l = key.getTranslatedKeyMessage().getString();
        return !l.contains("-1") && !l.toLowerCase(java.util.Locale.ROOT).contains("unknown");
    }

    public String keyLabel() {
        return bound() ? key.getTranslatedKeyMessage().getString() : "None";
    }
}
