package com.respawnanchorbreaker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Respawn anchor market data from donut.auction's JSON backend (undocumented, unofficial: see README).
 *
 * Requests are asynchronous (never on the game thread), rate-limited, backed off after failures, and the last good
 * result is cached. Trends are computed from our own recorded samples (persisted between sessions), so nothing is
 * invented: anything we don't have yet shows as unavailable.
 */
public final class Market {
    public enum Status { NO_DATA, LIVE, STALE, OFFLINE }

    public static final class Snapshot {
        public final double price;
        public final long fetchedAt;
        public final String priceField;
        public final Double volume;
        public final Double sales;

        Snapshot(double price, long fetchedAt, String priceField, Double volume, Double sales) {
            this.price = price;
            this.fetchedAt = fetchedAt;
            this.priceField = priceField;
            this.volume = volume;
            this.sales = sales;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Gson GSON = new Gson();
    private static final String[] PRICE_KEYS = {"marketValue", "market_value", "value", "currentPrice", "current_price",
            "price", "medianPrice", "median_price", "median", "avgPrice", "average", "lowestPrice", "lowest_price"};
    private static final String[] VOLUME_KEYS = {"volume24h", "volume_24h", "volume"};
    private static final String[] SALES_KEYS = {"sales24h", "sales_24h", "sales"};
    private static final String[] NAME_KEYS = {"itemName", "name", "id", "itemId", "slug", "displayName",
            "display_name", "item", "item_name", "material"};

    private static volatile Snapshot last;
    private static volatile String error;
    private static volatile boolean inFlight;
    private static volatile long nextFetch;
    private static volatile int failures;

    /** [epochSeconds, price], oldest first. Guarded by itself. */
    private static final List<double[]> HISTORY = new ArrayList<>();
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;
    private static boolean hookAdded;

    private Market() { }

    // ------------------------------------------------------------ scheduling (called every client tick, cheap)

    public static void tick(Config cfg) {
        if (!cfg.marketOverlay) return;
        long now = System.currentTimeMillis();
        if (!loaded) {
            loaded = true;
            loadHistory();
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Market::saveHistory, "anchor-market-save"));
            }
        }
        if (dirty && now - lastSave > 600_000L) saveHistory();
        if (inFlight || now < nextFetch) return;
        fetch(cfg, now);
    }

    private static void fetch(Config cfg, long now) {
        inFlight = true;
        long refresh = cfg.marketRefreshSeconds * 1000L;
        List<String> urls = new ArrayList<>();
        urls.add(cfg.marketUrl);
        for (String u : new String[]{"https://api.donut.auction/v2/tickers/",
                "https://api.donut.auction/v2/items/search?q=respawn%20anchor"}) {
            if (!urls.contains(u)) urls.add(u);
        }
        attempt(urls, 0, cfg.marketQuery, refresh, new StringBuilder());
    }

    /** Tries each endpoint in turn until one gives a usable price. Everything runs off the game thread. */
    private static void attempt(List<String> urls, int idx, String query, long refresh, StringBuilder why) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(urls.get(idx)))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "RespawnAnchorBreaker/1.0 (client mod; prices via donut.auction)")
                    .header("Accept", "application/json")
                    .GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                String problem;
                try {
                    if (err != null) {
                        problem = "connection problem (" + root(err) + ")";
                    } else if (resp.statusCode() != 200) {
                        problem = "HTTP " + resp.statusCode();
                    } else {
                        problem = parse(resp.body(), query, refresh);
                        if (problem != null) {
                            String body = resp.body();
                            String excerpt = body.length() > 220 ? body.substring(0, 220) + "..." : body;
                            ActivityLog.warnEvery("market-body-" + idx, 300_000, "Market",
                                    "Unrecognised response from " + urls.get(idx) + " : " + excerpt.replace('\n', ' '));
                        }
                    }
                } catch (Exception e) {
                    problem = "unreadable response (" + e.getClass().getSimpleName() + ")";
                }
                if (problem == null) {
                    inFlight = false;
                    return;
                }
                if (why.length() > 0) why.append("; ");
                why.append(urls.get(idx).replace("https://api.donut.auction", "")).append(": ").append(problem);
                if (idx + 1 < urls.size()) {
                    attempt(urls, idx + 1, query, refresh, why);
                } else {
                    fail(why.toString(), refresh);
                    inFlight = false;
                }
            });
        } catch (Exception e) { // bad URL etc.
            if (idx + 1 < urls.size()) {
                attempt(urls, idx + 1, query, refresh, why);
            } else {
                fail("bad request (" + e.getClass().getSimpleName() + ")", refresh);
                inFlight = false;
            }
        }
    }

    private static String root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 40 ? m.substring(0, 40) : m));
    }

    private static void fail(String why, long refreshMs) {
        failures++;
        error = why;
        long backoff = Math.min(300_000L, refreshMs * (1L << Math.min(failures, 4)));
        nextFetch = System.currentTimeMillis() + backoff;
        if (last == null) {
            ActivityLog.warnEvery("market-fail", 120_000, "Market", "Unable to retrieve market data (" + why + "). No cached data yet.");
        } else {
            ActivityLog.warnEvery("market-fail", 120_000, "Market", "Unable to retrieve current market data (" + why + "). Using cached data.");
        }
    }

    // ------------------------------------------------------------ parsing (defensive: schema is undocumented)

    /** Returns null on success (and stores the result), otherwise a short reason. */
    private static String parse(String body, String query, long refreshMs) {
        JsonElement root = JsonParser.parseString(body);
        JsonObject item = findItem(root, query);
        if (item == null) return "no \"" + query + "\" entry";
        String[] found = new String[1];
        Double price = pick(item, PRICE_KEYS, found);
        if (price == null || price <= 0) return "entry has no price field (keys: " + keys(item) + ")";
        Double volume = pick(item, VOLUME_KEYS, null);
        Double sales = pick(item, SALES_KEYS, null);
        long now = System.currentTimeMillis();
        boolean recovered = error != null;
        last = new Snapshot(price, now, found[0], volume, sales);
        error = null;
        failures = 0;
        nextFetch = now + refreshMs;
        addSample(now / 1000.0, price);
        if (recovered) ActivityLog.info("Market", "Market data connection restored.");
        ActivityLog.infoEvery("market-ok", 300_000, "Market", "Anchor pricing data updated.");
        return null;
    }

    private static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return s.replace(' ', '_').replace('-', '_');
    }

    private static JsonObject findItem(JsonElement e, String query) {
        if (e == null) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (matches(o, query)) return o;
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                JsonObject r = findItem(en.getValue(), query);
                if (r != null) return r;
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement c : a) {
                JsonObject r = findItem(c, query);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean matches(JsonObject o, String query) {
        String q = norm(query);
        for (String k : NAME_KEYS) {
            JsonElement v = o.get(k);
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                    && norm(v.getAsString()).equals(q)) return true;
        }
        return false;
    }

    private static JsonElement getIgnoreCase(JsonObject o, String key) {
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            if (en.getKey().equalsIgnoreCase(key)) return en.getValue();
        }
        return null;
    }

    private static Double pick(JsonObject o, String[] keys, String[] foundKey) {
        for (String k : keys) {
            Double d = num(getIgnoreCase(o, k), 0);
            if (d != null) {
                if (foundKey != null) foundKey[0] = k;
                return d;
            }
        }
        return null;
    }

    private static Double num(JsonElement v, int depth) {
        if (v == null || v.isJsonNull()) return null;
        if (v.isJsonPrimitive()) {
            try {
                if (v.getAsJsonPrimitive().isNumber()) return v.getAsDouble();
                return Double.parseDouble(v.getAsString().replace(",", "").replace("$", "").trim());
            } catch (Exception e) {
                return null;
            }
        }
        if (v.isJsonObject() && depth < 1) {
            for (String k : new String[]{"value", "median", "current", "amount", "price"}) {
                Double d = num(getIgnoreCase(v.getAsJsonObject(), k), depth + 1);
                if (d != null) return d;
            }
        }
        return null;
    }

    private static String keys(JsonObject o) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : o.keySet()) {
            if (n++ >= 10) { sb.append("..."); break; }
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ history (our own samples, persisted)

    private static void addSample(double tSec, double price) {
        synchronized (HISTORY) {
            if (!HISTORY.isEmpty() && tSec - HISTORY.get(HISTORY.size() - 1)[0] < 30) return;
            HISTORY.add(new double[]{tSec, price});
            double cutoff = tSec - 8 * 86400.0;
            while (!HISTORY.isEmpty() && HISTORY.get(0)[0] < cutoff) HISTORY.remove(0);
            if (HISTORY.size() > 20000) HISTORY.subList(0, 2000).clear();
            dirty = true;
        }
    }

    private static Path historyPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("respawn_anchor_breaker_market.json");
    }

    private static void loadHistory() {
        try {
            Path p = historyPath();
            if (!Files.exists(p)) return;
            try (Reader r = Files.newBufferedReader(p)) {
                double[][] data = GSON.fromJson(r, double[][].class);
                if (data == null) return;
                synchronized (HISTORY) {
                    for (double[] s : data) if (s != null && s.length == 2 && s[1] > 0) HISTORY.add(s);
                }
            }
        } catch (Exception e) {
            ActivityLog.warn("Market", "Could not read saved price history; starting fresh.");
        }
    }

    private static void saveHistory() {
        try {
            double[][] data;
            synchronized (HISTORY) {
                data = HISTORY.toArray(new double[0][]);
                dirty = false;
            }
            lastSave = System.currentTimeMillis();
            try (Writer w = Files.newBufferedWriter(historyPath())) {
                GSON.toJson(data, w);
            }
        } catch (Exception ignored) {
            // best effort; the overlay works without it
        }
    }

    // ------------------------------------------------------------ queries for the overlay

    public static Snapshot last() { return last; }

    public static String error() { return error; }

    public static Status status() {
        Snapshot s = last;
        if (s == null) return error != null ? Status.OFFLINE : Status.NO_DATA;
        long age = System.currentTimeMillis() - s.fetchedAt;
        long fresh = Config.get().marketRefreshSeconds * 3000L + 10_000L;
        return (error == null && age <= fresh) ? Status.LIVE : Status.STALE;
    }

    /** Percent change versus the newest sample at least windowSec old, or null while there isn't that much history. */
    public static Double changePct(long windowSec) {
        Snapshot s = last;
        if (s == null) return null;
        double target = s.fetchedAt / 1000.0 - windowSec;
        synchronized (HISTORY) {
            double[] past = null;
            for (double[] h : HISTORY) {
                if (h[0] <= target) past = h; else break;
            }
            if (past == null || past[1] <= 0) return null;
            return (s.price - past[1]) / past[1] * 100.0;
        }
    }

    /** {percentChange, minutesCovered} since our first recorded sample, or null with fewer than two samples. */
    public static double[] sinceStart() {
        Snapshot s = last;
        if (s == null) return null;
        synchronized (HISTORY) {
            if (HISTORY.size() < 2) return null;
            double[] first = HISTORY.get(0);
            double minutes = (s.fetchedAt / 1000.0 - first[0]) / 60.0;
            if (minutes < 2 || first[1] <= 0) return null;
            return new double[]{(s.price - first[1]) / first[1] * 100.0, minutes};
        }
    }

    /** {low, high} over the last 24 hours of our samples, or null with fewer than 3 samples. */
    public static double[] range24h() {
        Snapshot s = last;
        if (s == null) return null;
        double cutoff = s.fetchedAt / 1000.0 - 86400;
        synchronized (HISTORY) {
            double lo = Double.MAX_VALUE, hi = 0;
            int n = 0;
            for (double[] h : HISTORY) {
                if (h[0] < cutoff) continue;
                lo = Math.min(lo, h[1]);
                hi = Math.max(hi, h[1]);
                n++;
            }
            return n < 3 ? null : new double[]{lo, hi};
        }
    }

    public static int samples() {
        synchronized (HISTORY) { return HISTORY.size(); }
    }
}
