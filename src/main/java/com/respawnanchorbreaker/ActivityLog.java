package com.respawnanchorbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-readable activity log: what the automation is doing and why. Thread-safe (the market thread logs too),
 * de-duplicated (identical lines collapse into "x3"), rate-limited per key, and capped in size.
 */
public final class ActivityLog {
    public enum Level { INFO, WARN, IMPORTANT }

    public static final class Entry {
        public long time;
        public final Level level;
        public final String category;
        public final String message;
        public int count = 1;

        Entry(long time, Level level, String category, String message) {
            this.time = time;
            this.level = level;
            this.category = category;
            this.message = message;
        }

        public String text() {
            return count > 1 ? message + "  x" + count : message;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("respawn_anchor_breaker/activity");
    private static final int MAX = 400;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Long> LAST_SHOWN = new HashMap<>();
    private static final Map<String, Deque<Long>> REPEATS = new HashMap<>();
    private static final List<String> CHAT = new ArrayList<>();

    private ActivityLog() { }

    public static synchronized void add(Level level, String category, String message) {
        long now = System.currentTimeMillis();
        Entry last = ENTRIES.isEmpty() ? null : ENTRIES.get(ENTRIES.size() - 1);
        if (last != null && last.level == level && last.category.equals(category)
                && last.message.equals(message) && now - last.time < 15000) {
            last.count++;
            last.time = now;
        } else {
            ENTRIES.add(new Entry(now, level, category, message));
            if (ENTRIES.size() > MAX) ENTRIES.subList(0, 50).clear();
        }
        LOG.info("[{}] {}", category, message);
        if (level != Level.INFO && Config.get().logToChat && CHAT.size() < 20) {
            CHAT.add("[Anchor] [" + category + "] " + message);
        }
        watchForLoops(category, message, now);
    }

    /** Warns once in a while if the very same thing keeps happening: usually a sign of a loop. */
    private static void watchForLoops(String category, String message, long now) {
        if (category.equals("Loop")) return;
        String key = category + "|" + message;
        Deque<Long> q = REPEATS.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > 600000) q.pollFirst();
        if (q.size() >= 6 && allow("loop|" + key, 300000, now)) {
            String shortMsg = message.length() > 60 ? message.substring(0, 60) + "..." : message;
            ENTRIES.add(new Entry(now, Level.WARN, "Loop",
                    "Possible loop: \"" + shortMsg + "\" happened " + q.size() + " times in 10 minutes."));
        }
    }

    private static boolean allow(String key, long intervalMs, long now) {
        Long prev = LAST_SHOWN.get(key);
        if (prev != null && now - prev < intervalMs) return false;
        LAST_SHOWN.put(key, now);
        if (LAST_SHOWN.size() > 300) LAST_SHOWN.clear();
        return true;
    }

    public static void info(String category, String message) { add(Level.INFO, category, message); }

    public static void warn(String category, String message) { add(Level.WARN, category, message); }

    public static void important(String category, String message) { add(Level.IMPORTANT, category, message); }

    /** Logs at most once per intervalMs for this key, so per-tick situations don't flood the log. */
    public static synchronized void infoEvery(String key, long intervalMs, String category, String message) {
        if (allow(key, intervalMs, System.currentTimeMillis())) add(Level.INFO, category, message);
    }

    public static synchronized void warnEvery(String key, long intervalMs, String category, String message) {
        if (allow(key, intervalMs, System.currentTimeMillis())) add(Level.WARN, category, message);
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        REPEATS.clear();
    }

    public static synchronized List<String> drainChat() {
        if (CHAT.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(CHAT);
        CHAT.clear();
        return out;
    }
}
