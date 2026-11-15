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
    /** Detection range = your block reach (4.5 survival, 5 creative) minus this margin. */
    public double reachMargin = 0.3;
    /** Also mine a snow layer on/next to an anchor when it blocks the view of it. */
    public boolean clearSnow = true;
    /** Put anchors into an ender chest (placed from your hotbar) once you carry depositThreshold of them. */
    public boolean depositAnchors = false;
    /** Anchors carried before depositing: 32 (half a stack) to 640 (10 stacks), in steps of 32. */
    public int depositThreshold = 320;
    /** After depositing, mine the ender chest back (needs a Silk Touch pickaxe in the pickaxe slot). */
    public boolean retrieveChest = true;
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
    /** After each broken anchor, eat food from foodSlot. */
    public boolean autoEat = true;
    /** Hotbar slot (1-9) holding food. */
    public int foodSlot = 4;
    /** How many pieces to try to eat after each anchor (1-10). Skips if already full. */
    public int eatCount = 2;
    /** When no anchors are left nearby, type gotoCommand in chat (for Baritone). */
    public boolean baritoneGoto = true;
    /** Sent as a chat message. Without Baritone installed this would appear in server chat! */
    public String gotoCommand = "#goto minecraft:respawn_anchor";

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
        reachMargin = Math.max(0.0, Math.min(2.0, reachMargin));
        depositThreshold = Math.max(32, Math.min(640, Math.round(depositThreshold / 32.0F) * 32));
        scanIntervalTicks = Math.max(1, Math.min(100, scanIntervalTicks));
        pickaxeSlot = Math.max(1, Math.min(9, pickaxeSlot));
        maxDropBlocks = Math.max(1, Math.min(64, maxDropBlocks));
        foodSlot = Math.max(1, Math.min(9, foodSlot));
        eatCount = Math.max(1, Math.min(10, eatCount));
        if (gotoCommand == null || gotoCommand.isBlank()) gotoCommand = "#goto minecraft:respawn_anchor";
    }
}
