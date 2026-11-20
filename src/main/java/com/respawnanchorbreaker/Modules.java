package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Registry of every module: what the menu lists, what its settings page shows, and its optional keybind. */
public final class Modules {
    public static final String[] CATEGORIES = {"Mining", "Support", "Navigation", "Overlays"};
    public static final List<Module> ALL = new ArrayList<>();
    private static boolean registered;

    private static Config c() { return Config.get(); }

    static {
        // ---------------------------------------------------------------- Mining
        add(new Module("anchor_mining", "Anchor Mining",
                "Master switch. Finds, aims at and breaks respawn anchors in reach.", "Mining",
                () -> c().enabled, v -> c().enabled = v)
                .decimal("Aim distance", "Farthest anchor it will aim at. Lower it if it stares at ones it can't reach.",
                        () -> c().aimDistance, v -> c().aimDistance = v, 2.0, 5.0, 0.1)
                .integer("Scan interval", "Ticks between searches while idle.",
                        () -> c().scanIntervalTicks, v -> c().scanIntervalTicks = v, 1, 40, 1));
        add(new Module("cluster_mining", "Cluster Mining",
                "Breaks every anchor in a cluster before collecting any drops.", "Mining",
                () -> c().clusterMining, v -> c().clusterMining = v)
                .integer("Cluster radius", "Anchors this close count as one cluster.",
                        () -> c().clusterRadius, v -> c().clusterRadius = v, 3, 12, 1));
        add(new Module("auto_swap", "Pickaxe Swap",
                "Selects the pickaxe slot when about to mine, at most once per cooldown.", "Mining",
                () -> c().autoSwap, v -> c().autoSwap = v)
                .integer("Pickaxe slot", "Hotbar slot (1-9) holding your pickaxe.",
                        () -> c().pickaxeSlot, v -> c().pickaxeSlot = v, 1, 9, 1)
                .integer("Swap cooldown", "Ticks between swaps (20 = once a second).",
                        () -> c().swapCooldownTicks, v -> c().swapCooldownTicks = v, 5, 100, 1));
        add(new Module("clear_snow", "Clear Snow",
                "Mines a snow layer that covers an anchor.", "Mining",
                () -> c().clearSnow, v -> c().clearSnow = v));
        add(new Module("auto_pickup", "Auto Pickup",
                "Walks to dropped anchors and picks them up.", "Mining",
                () -> c().autoPickup, v -> c().autoPickup = v)
                .integer("Max drop", "Biggest fall it will walk off (blocks).",
                        () -> c().maxDropBlocks, v -> c().maxDropBlocks = v, 1, 64, 1));
        add(new Module("water_mining", "Water Mining",
                "Swims to anchors underwater, mines and collects them, then returns to land.", "Mining",
                () -> c().waterMining, v -> c().waterMining = v)
                .integer("Water range", "How far away an underwater anchor triggers it (blocks).",
                        () -> c().waterRange, v -> c().waterRange = v, 6, 48, 1));
        add(new Module("item_assist", "Unreachable Drops",
                "If a drop can't be reached, asks Baritone to path to it (it can place and break blocks).", "Mining",
                () -> c().itemAssist, v -> c().itemAssist = v));

        // ---------------------------------------------------------------- Support
        add(new Module("food", "Food",
                "Eats before hunger stops you sprinting, pausing Baritone, then resumes.", "Support",
                () -> c().autoEat, v -> c().autoEat = v)
                .integer("Food slot", "Hotbar slot (1-9) holding your food.",
                        () -> c().foodSlot, v -> c().foodSlot = v, 1, 9, 1)
                .toggle("Eat when hungry", "Eat when the food bar gets low.", () -> c().hungerEat, v -> c().hungerEat = v)
                .integer("Hunger threshold", "Start eating at or below this food level (20 = full, sprint stops at 6).",
                        () -> c().hungerThreshold, v -> c().hungerThreshold = v, 4, 19, 1)
                .integer("Eat until", "Keep eating until at least this food level.",
                        () -> c().hungerTarget, v -> c().hungerTarget = v, 8, 20, 1)
                .toggle("Resume navigation", "Send the goto again after eating.",
                        () -> c().resumeAfterFood, v -> c().resumeAfterFood = v)
                .toggle("Eat after each anchor", "Also eat after every broken anchor.",
                        () -> c().eatAfterAnchor, v -> c().eatAfterAnchor = v)
                .integer("Pieces per anchor", "How many to eat after an anchor.",
                        () -> c().eatCount, v -> c().eatCount = v, 1, 10, 1)
                .toggle("Refill food", "Move food from the inventory into the food slot when it runs out.",
                        () -> c().foodRefill, v -> c().foodRefill = v));
        add(new Module("cobble_refill", "Cobble Refill",
                "Refills the hotbar with cobblestone from the inventory when it runs out.", "Support",
                () -> c().cobbleRefill, v -> c().cobbleRefill = v)
                .integer("Hotbar slot", "Slot to refill (1-9), 0 = pick a free one.",
                        () -> c().cobbleSlot, v -> c().cobbleSlot = v, 0, 9, 1));
        add(new Module("deposit", "Deposit Anchors",
                "Places an ender chest from the hotbar and stores anchors in it.", "Support",
                () -> c().depositAnchors, v -> c().depositAnchors = v)
                .integer("Deposit at", "Anchors carried before depositing (32 = half a stack).",
                        () -> c().depositThreshold, v -> c().depositThreshold = v, 32, 640, 32)
                .toggle("Retrieve chest", "Mine the chest back afterwards (needs Silk Touch).",
                        () -> c().retrieveChest, v -> c().retrieveChest = v));
        add(new Module("health_guard", "Health Guard",
                "On low health: cancel Baritone, eat, heal, then resume.", "Support",
                () -> c().healthGuard, v -> c().healthGuard = v)
                .integer("Low health (HP)", "2 HP = 1 heart. 5 = 2.5 hearts.",
                        () -> c().lowHealthHp, v -> c().lowHealthHp = v, 1, 19, 1));
        add(new Module("totem_guard", "Totem Guard",
                "After a totem pop, opens the inventory until AutoTotem refills the offhand.", "Support",
                () -> c().totemGuard, v -> c().totemGuard = v)
                .integer("Timeout (s)", "Seconds to wait for AutoTotem before giving up.",
                        () -> c().totemTimeoutSeconds, v -> c().totemTimeoutSeconds = v, 15, 180, 5));

        // ---------------------------------------------------------------- Navigation
        add(new Module("baritone", "Baritone Goto",
                "Sends the goto command when nothing is in reach, and cancel when it must take over.", "Navigation",
                () -> c().baritoneGoto, v -> c().baritoneGoto = v)
                .text("Goto command", "Sent in chat to start navigation.", () -> c().gotoCommand, v -> c().gotoCommand = v)
                .text("Cancel command", "Sent in chat to stop Baritone.",
                        () -> c().baritoneCancelCommand, v -> c().baritoneCancelCommand = v)
                .toggle("Coordinate fallback", "If Baritone can't find an anchor, send the coordinates of one we've seen.",
                        () -> c().gotoCoordFallback, v -> c().gotoCoordFallback = v)
                .integer("Idle watchdog (s)", "Nothing happening this long: send Baritone to the closest known anchor.",
                        () -> c().idleWatchdogSeconds, v -> c().idleWatchdogSeconds = v, 10, 300, 5));
        add(new Module("stuck_recovery", "Stuck Recovery",
                "Restarts the goto if Baritone stops making progress.", "Navigation",
                () -> c().stuckRecovery, v -> c().stuckRecovery = v)
                .integer("Stall time (s)", "Seconds without progress before restarting.",
                        () -> c().stuckSeconds, v -> c().stuckSeconds = v, 30, 300, 5)
                .integer("Max restarts", "Restarts in 10 minutes before it escalates to an RTP.",
                        () -> c().navMaxRestarts, v -> c().navMaxRestarts = v, 1, 10, 1));
        add(new Module("auto_rtp", "Auto RTP",
                "Random teleport as a recovery action, with a cooldown and an hourly cap.", "Navigation",
                () -> c().rtpEnabled, v -> c().rtpEnabled = v)
                .toggle("No anchors nearby", "RTP if none are in render distance.", () -> c().rtpNoAnchor, v -> c().rtpNoAnchor = v)
                .integer("No-anchor time (s)", "How long with none before RTP.",
                        () -> c().rtpNoAnchorSeconds, v -> c().rtpNoAnchorSeconds = v, 20, 600, 5)
                .toggle("Baritone failure", "RTP when Baritone reports no path/anchors.", () -> c().rtpOnNavFail, v -> c().rtpOnNavFail = v)
                .toggle("Nearby player", "RTP when another player is close.", () -> c().rtpOnPlayer, v -> c().rtpOnPlayer = v)
                .integer("Player distance", "Blocks.", () -> c().rtpPlayerRadius, v -> c().rtpPlayerRadius = v, 20, 400, 5)
                .integer("Cooldown (s)", "Minimum time between RTPs.", () -> c().rtpCooldownSeconds, v -> c().rtpCooldownSeconds = v, 10, 600, 5)
                .integer("Max per hour", "Hard cap on RTPs per hour.", () -> c().rtpMaxPerHour, v -> c().rtpMaxPerHour = v, 1, 120, 1)
                .toggle("Maximise profit", "RTP when the nearest anchor is very far and the area is sparse.",
                        () -> c().profitRtp, v -> c().profitRtp = v)
                .integer("Far distance", "Blocks to the nearest anchor before the area counts as too far.",
                        () -> c().profitFarDistance, v -> c().profitFarDistance = v, 60, 400, 10)
                .text("RTP command", "Sent to teleport.", () -> c().rtpCommand, v -> c().rtpCommand = v)
                .integer("Menu slot", "If /rtp opens a menu: slot to click (-1 = none).",
                        () -> c().rtpMenuSlot, v -> c().rtpMenuSlot = v, -1, 53, 1));
        add(new Module("flood_avoider", "Flood Avoider",
                "Cancels Baritone and RTPs when it pillars up over a wall.", "Navigation",
                () -> c().floodAvoider, v -> c().floodAvoider = v)
                .integer("Sensitivity", "Pillar steps in one column before it counts (lower = quicker).",
                        () -> c().floodSensitivity, v -> c().floodSensitivity = v, 3, 15, 1)
                .toggle("RTP instead", "RTP after cancelling (otherwise just restart the goto).",
                        () -> c().floodRtp, v -> c().floodRtp = v));
        add(new Module("trident", "Rain Trident",
                "In rain, Riptide to the closest anchor, hop after hop, even far away.", "Navigation",
                () -> c().tridentEnabled, v -> c().tridentEnabled = v)
                .integer("Trident slot", "Hotbar slot (1-9).", () -> c().tridentSlot, v -> c().tridentSlot = v, 1, 9, 1)
                .integer("Max distance", "Farthest anchor to head for (blocks).",
                        () -> c().tridentRange, v -> c().tridentRange = v, 10, 512, 10)
                .integer("Ascent angle", "Every hop launches up at this angle (degrees) toward the anchor.",
                        () -> c().tridentAscent, v -> c().tridentAscent = v, 15, 75, 5)
                .integer("Lane length", "Clear lane required ahead of each launch.",
                        () -> c().tridentHop, v -> c().tridentHop = v, 6, 40, 1)
                .integer("Min health (HP)", "Won't launch below this.",
                        () -> c().tridentMinHealth, v -> c().tridentMinHealth = v, 4, 20, 1));

        // ---------------------------------------------------------------- Overlays
        add(new Module("credit_overlay", "Credit Card", "The Anchor Addon card (GitHub, Temple, Discord).", "Overlays",
                () -> c().showOverlay, v -> c().showOverlay = v));
        add(new Module("market_overlay", "Market", "Respawn anchor price, trend and buy/sell hints from donut.auction.", "Overlays",
                () -> c().marketOverlay, v -> c().marketOverlay = v)
                .integer("Refresh (s)", "Seconds between updates (30+).",
                        () -> c().marketRefreshSeconds, v -> c().marketRefreshSeconds = v, 30, 900, 10)
                .toggle("Show changes", "5 min / 1 h / 24 h change.", () -> c().marketShowChanges, v -> c().marketShowChanges = v)
                .toggle("Show range", "24 h low/high and where the price sits.", () -> c().marketShowRange, v -> c().marketShowRange = v)
                .toggle("Show volume", "24 h volume/sales when the site reports it.", () -> c().marketShowVolume, v -> c().marketShowVolume = v));
        add(new Module("stats_overlay", "Farm Stats", "Anchors mined, average per hour, what it is doing now.", "Overlays",
                () -> c().statsOverlay, v -> c().statsOverlay = v)
                .button("Reset session", "Zero the session counters.", Stats::resetSession));
        add(new Module("keys_overlay", "Keybinds List", "Lists module keybinds (appears once any is assigned).", "Overlays",
                () -> c().keysOverlay, v -> c().keysOverlay = v));
        add(new Module("log_overlay", "Log Overlay", "Latest activity log lines on screen.", "Overlays",
                () -> c().logOverlay, v -> c().logOverlay = v)
                .integer("Lines", "How many lines to show.", () -> c().logLines, v -> c().logLines = v, 3, 15, 1)
                .toggle("Also in chat", "Mirror warnings and important events into chat.",
                        () -> c().logToChat, v -> c().logToChat = v)
                .button("Clear log", "Erase the activity log.", ActivityLog::clear));
    }

    private Modules() { }

    private static void add(Module m) { ALL.add(m); }

    public static List<Module> inCategory(String cat) {
        List<Module> out = new ArrayList<>();
        for (Module m : ALL) if (m.category.equals(cat)) out.add(m);
        return out;
    }

    /** Registers one (initially unbound) key per module so keybinds persist in options.txt and show in Controls. */
    public static void registerKeys() {
        if (registered) return;
        registered = true;
        for (Module m : ALL) {
            m.key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.respawn_anchor_breaker.module." + m.id,
                    InputConstants.Type.KEYBOARD,
                    InputConstants.UNKNOWN.getValue(),
                    KeyMapping.Category.MISC));
        }
    }

    private static boolean sanitized;

    /**
     * An earlier build registered the module keys with a raw "-1" key code. Minecraft saved that into options.txt,
     * and a -1 key reads outside the keyboard-state array (crash when joining a server, mouse not captured after
     * closing a menu). Reset any such key to "unbound" once at startup and save the clean options.
     */
    private static void sanitizeKeys(Minecraft mc) {
        sanitized = true;
        boolean fixed = false;
        for (Module m : ALL) {
            if (m.key == null) continue;
            String l = m.key.getTranslatedKeyMessage().getString();
            if (l.contains("-1") || (!m.key.isUnbound() && l.toLowerCase(java.util.Locale.ROOT).contains("unknown"))) {
                m.key.setKey(InputConstants.UNKNOWN);
                fixed = true;
            }
        }
        if (fixed) {
            KeyMapping.resetMapping();
            mc.options.save();
            ActivityLog.info("System", "Reset invalid module keybinds left by an older build.");
        }
    }

    public static void tickKeys(Minecraft mc) {
        if (!sanitized) sanitizeKeys(mc);
        if (mc.player == null) return;
        for (Module m : ALL) {
            if (m.key == null) continue;
            while (m.key.consumeClick()) {
                m.toggle();
                Config.save();
                ActivityLog.info("Module", m.name + (m.enabled() ? " enabled" : " disabled") + " by keybind.");
            }
        }
    }

    public static boolean anyBound() {
        for (Module m : ALL) if (m.bound()) return true;
        return false;
    }
}
