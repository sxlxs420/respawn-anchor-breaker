package com.respawnanchorbreaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON config stored at config/respawn_anchor_breaker.json. */
public final class Config {
    private static final Logger LOG = LoggerFactory.getLogger("respawn_anchor_breaker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    /** Master on/off switch (also toggled by the keybind). */
    public boolean enabled = true;
    /** Detection radius in blocks (1-32). */
    public int radius = 10;
    /** How often (in ticks) to scan for anchors while no target is selected (1-100). */
    public int scanIntervalTicks = 10;
    /** Switch to the pickaxe hotbar slot before breaking an anchor. */
    public boolean autoSwap = true;
    /** Hotbar slot (1-9) holding the pickaxe. */
    public int pickaxeSlot = 3;
    /** Walk to dropped respawn anchor items and pick them up. */
    public boolean autoPickup = true;
    /** Largest drop (in blocks) it will walk off while going for a dropped anchor (1-64). */
    public int maxDropBlocks = 20;

    public static Config get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("respawn_anchor_breaker.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Config c = GSON.fromJson(r, Config.class);
                    if (c != null) instance = c;
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not read config, using defaults", e);
            instance = new Config();
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            LOG.warn("Could not write config", e);
        }
    }

    private void sanitize() {
        radius = Math.max(1, Math.min(32, radius));
        scanIntervalTicks = Math.max(1, Math.min(100, scanIntervalTicks));
        pickaxeSlot = Math.max(1, Math.min(9, pickaxeSlot));
        maxDropBlocks = Math.max(1, Math.min(64, maxDropBlocks));
    }
}
