package com.respawnanchorbreaker;

import java.util.ArrayDeque;

/** Farm statistics: anchors mined this session, average per hour, recent rate, deposits. */
public final class Stats {
    private static long start = System.currentTimeMillis();
    private static int sessionMined;
    private static long deposited;
    private static final ArrayDeque<Long> TIMES = new ArrayDeque<>();

    private Stats() { }

    public static synchronized void recordMined() {
        long now = System.currentTimeMillis();
        sessionMined++;
        Config.get().lifetimeMined++;
        TIMES.addLast(now);
        while (!TIMES.isEmpty() && now - TIMES.peekFirst() > 3_900_000L) TIMES.pollFirst();
        if (Config.get().lifetimeMined % 25 == 0) Config.save();
    }

    public static synchronized void recordDeposited(int n) {
        deposited += n;
        Config.get().lifetimeDeposited += n;
    }

    public static synchronized int session() { return sessionMined; }

    public static synchronized long deposited() { return deposited; }

    public static synchronized long elapsedMs() { return System.currentTimeMillis() - start; }

    /** Average anchors per hour over the whole session (null in the first two minutes: too noisy). */
    public static synchronized Double avgPerHour() {
        long ms = elapsedMs();
        if (ms < 120_000L) return null;
        return sessionMined / (ms / 3_600_000.0);
    }

    /** Anchors per hour measured over the last N minutes (null if the session is younger than that). */
    public static synchronized Double rate(int minutes) {
        long now = System.currentTimeMillis();
        long ms = minutes * 60_000L;
        if (elapsedMs() < ms) return null;
        int n = 0;
        for (long t : TIMES) if (now - t <= ms) n++;
        return n * 60.0 / minutes;
    }

    public static synchronized void resetSession() {
        start = System.currentTimeMillis();
        sessionMined = 0;
        deposited = 0;
        TIMES.clear();
    }

    public static String uptime() {
        long s = elapsedMs() / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
