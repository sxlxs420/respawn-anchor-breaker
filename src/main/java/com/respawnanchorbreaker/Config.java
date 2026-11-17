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

    /** Bumped when defaults change so old config files get migrated once. */
    public int configVersion = 0;
    /** Master on/off switch (also toggled by the keybind). */
    public boolean enabled = true;
    /** Farthest anchor (blocks) it will aim at. Lower it if it stares at anchors it can't reach. */
    public double aimDistance = 4.2;
    /** Re-select the pickaxe slot at most once per this many ticks (20 = once a second). */
    public int swapCooldownTicks = 20;
    /** Clear every anchor in a cluster before collecting drops. */
    public boolean clusterMining = true;
    public int clusterRadius = 6;
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
    /** Also eat after every broken anchor (the original behaviour). */
    public boolean eatAfterAnchor = true;
    /** Eat when hunger gets close to the point where sprinting stops (sprinting needs more than 6). */
    public boolean hungerEat = true;
    /** Start eating at or below this food level (20 = full). */
    public int hungerThreshold = 10;
    /** Keep eating until at least this food level. */
    public int hungerTarget = 20;
    /** Move food from the inventory into the food slot when the slot runs out. */
    public boolean foodRefill = true;
    /** Send the previous goto again after eating. */
    public boolean resumeAfterFood = true;
    /** Refill the hotbar with cobblestone from the inventory when the hotbar has none. */
    public boolean cobbleRefill = true;
    /** Hotbar slot (1-9) to refill, or 0 to pick a free one automatically. */
    public int cobbleSlot = 0;
    /** Restart Baritone when it stops making progress. */
    public boolean stuckRecovery = true;
    public int stuckSeconds = 90;
    /** Restarts within 10 minutes before it escalates to an RTP. */
    public int navMaxRestarts = 3;
    /** Automatic random teleport as a recovery action. */
    public boolean rtpEnabled = true;
    public boolean rtpNoAnchor = true;
    public int rtpNoAnchorSeconds = 60;
    public boolean rtpOnNavFail = true;
    public boolean rtpOnPlayer = true;
    public int rtpPlayerRadius = 100;
    public int rtpCooldownSeconds = 45;
    public int rtpMaxPerHour = 20;
    /** Command sent for a random teleport. */
    public String rtpCommand = "/rtp";
    /** If your server's /rtp opens a menu, the slot (0-based) to click. -1 = no menu. */
    public int rtpMenuSlot = -1;
    /** Swim to underwater anchors ourselves (Baritone is poor in water), then return to land. */
    public boolean waterMining = true;
    public int waterRange = 24;
    /** If a dropped anchor can't be reached, ask Baritone to path to it (it can place/break blocks). */
    public boolean itemAssist = true;
    /** RTP when the nearest anchor is very far to walk to and the area is sparse (stringent). */
    public boolean profitRtp = true;
    public int profitFarDistance = 150;
    /** Detect Baritone pillaring up over a wall and RTP instead. */
    public boolean floodAvoider = true;
    /** Pillar steps in one column before it counts as pillaring over a wall. */
    public int floodSensitivity = 6;
    public boolean floodRtp = true;
    /** Seconds to wait for AutoTotem before giving up on a totem swap. */
    public int totemTimeoutSeconds = 60;
    /** When no anchors are left nearby, type gotoCommand in chat (for Baritone). */
    public boolean baritoneGoto = true;
    /** Sent as a chat message. Without Baritone installed this would appear in server chat! */
    public String gotoCommand = "#goto minecraft:respawn_anchor";
    /** Sent when you get low on health or pop a totem, to stop Baritone. */
    public String baritoneCancelCommand = "#cancel";
    /** Below lowHealthHp: cancel Baritone, eat until full, wait for full health, then resume. */
    public boolean healthGuard = true;
    /** Health points (2 per heart) that count as low. 5 = 2.5 hearts. */
    public int lowHealthHp = 5;
    /** After a totem pop: open the inventory until AutoTotem refills the offhand, then close it. */
    public boolean totemGuard = true;
    /** In rain, use a Riptide trident to reach anchors that are out of reach (Baritone is cancelled first). */
    public boolean tridentEnabled = true;
    /** Hotbar slot (1-9) holding the Riptide trident. */
    public int tridentSlot = 8;
    /** Farthest anchor (blocks) it will launch toward. It looks for anchors across your whole render distance. */
    public int tridentRange = 256;
    /** Length of the clear lane it requires ahead of each launch. */
    public int tridentHop = 14;
    /** Won't launch below this many health points. */
    public int tridentMinHealth = 10;
    /** Show the small draggable credit card on screen. */
    public boolean showOverlay = true;
    public int overlayX = 6;
    public int overlayY = 6;
    /** Market overlay (prices from donut.auction). */
    public boolean marketOverlay = true;
    public int marketX = 6;
    public int marketY = 74;
    public int marketRefreshSeconds = 60;
    public boolean marketShowChanges = true;
    public boolean marketShowRange = true;
    public boolean marketShowVolume = true;
    public String marketQuery = "respawn_anchor";
    public String marketUrl = "https://api.donut.auction/v2/items/search?q=respawn_anchor";
    /** Farm statistics overlay. */
    public boolean statsOverlay = true;
    public int statsX = 6;
    public int statsY = 190;
    /** Keybinds list overlay (only shows once a module has a key). */
    public boolean keysOverlay = true;
    public int keysX = 200;
    public int keysY = 6;
    /** Activity log overlay. */
    public boolean logOverlay = false;
    public int logX = 200;
    public int logY = 60;
    public int logLines = 6;
    /** Mirror warnings and important events into the chat window. */
    public boolean logToChat = true;
    public long lifetimeMined = 0;
    public long lifetimeDeposited = 0;

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
        if (configVersion < 2) { // v2: far trident range and the new defaults
            tridentRange = Math.max(tridentRange, 256);
            configVersion = 2;
        }
        reachMargin = Math.max(0.0, Math.min(2.0, reachMargin));
        aimDistance = Math.max(2.0, Math.min(5.0, aimDistance));
        swapCooldownTicks = Math.max(5, Math.min(100, swapCooldownTicks));
        clusterRadius = Math.max(3, Math.min(12, clusterRadius));
        hungerThreshold = Math.max(4, Math.min(19, hungerThreshold));
        hungerTarget = Math.max(hungerThreshold + 1, Math.min(20, hungerTarget));
        cobbleSlot = Math.max(0, Math.min(9, cobbleSlot));
        stuckSeconds = Math.max(30, Math.min(300, stuckSeconds));
        navMaxRestarts = Math.max(1, Math.min(10, navMaxRestarts));
        rtpNoAnchorSeconds = Math.max(20, Math.min(600, rtpNoAnchorSeconds));
        rtpPlayerRadius = Math.max(20, Math.min(400, rtpPlayerRadius));
        rtpCooldownSeconds = Math.max(10, Math.min(600, rtpCooldownSeconds));
        rtpMaxPerHour = Math.max(1, Math.min(120, rtpMaxPerHour));
        rtpMenuSlot = Math.max(-1, Math.min(53, rtpMenuSlot));
        if (rtpCommand == null || rtpCommand.isBlank()) rtpCommand = "/rtp";
        floodSensitivity = Math.max(3, Math.min(15, floodSensitivity));
        waterRange = Math.max(6, Math.min(48, waterRange));
        profitFarDistance = Math.max(60, Math.min(400, profitFarDistance));
        totemTimeoutSeconds = Math.max(15, Math.min(180, totemTimeoutSeconds));
        tridentHop = Math.max(6, Math.min(40, tridentHop));
        tridentMinHealth = Math.max(4, Math.min(20, tridentMinHealth));
        marketRefreshSeconds = Math.max(30, Math.min(900, marketRefreshSeconds));
        logLines = Math.max(3, Math.min(15, logLines));
        if (marketUrl == null || marketUrl.isBlank()) marketUrl = "https://api.donut.auction/v2/items/search?q=respawn_anchor";
        if (marketQuery == null || marketQuery.isBlank()) marketQuery = "respawn_anchor";
        lowHealthHp = Math.max(1, Math.min(19, lowHealthHp));
        tridentSlot = Math.max(1, Math.min(9, tridentSlot));
        tridentRange = Math.max(10, Math.min(512, tridentRange));
        if (baritoneCancelCommand == null || baritoneCancelCommand.isBlank()) baritoneCancelCommand = "#cancel";
        depositThreshold = Math.max(32, Math.min(640, Math.round(depositThreshold / 32.0F) * 32));
        scanIntervalTicks = Math.max(1, Math.min(100, scanIntervalTicks));
        pickaxeSlot = Math.max(1, Math.min(9, pickaxeSlot));
        maxDropBlocks = Math.max(1, Math.min(64, maxDropBlocks));
        foodSlot = Math.max(1, Math.min(9, foodSlot));
        eatCount = Math.max(1, Math.min(10, eatCount));
        if (gotoCommand == null || gotoCommand.isBlank()) gotoCommand = "#goto minecraft:respawn_anchor";
    }
}
