package dev.hivebets.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Live market state in Redis. Nothing here is the source of truth; it is rebuilt from events.
 * Keys: mkt:{id}:last, :book, :candles (hash t → candle), :trades, :news, :spark (lists), :vol, :traders (set);
 * markets:live (set), lb:profit (zset), lb:row:{user} (json), eq:{user} (list), presence (zset).
 */
@Component
public class LiveState {
    private static final int TRADES = 50, NEWS = 30, SPARK = 40, CANDLES = 360, EQUITY = 360;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public LiveState(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    static String k(String id, String part) {
        return "mkt:" + id + ":" + part;
    }

    /**
     * Consumer idempotency: claims an event id, true only the first time. Delivery is at-least-once
     * (outbox relay, broker redelivery), so duplicates are expected and skipped here.
     */
    public boolean claim(String eventId) {
        Boolean ok = redis.opsForValue().setIfAbsent("evt:" + eventId, "1", Duration.ofHours(2));
        return Boolean.TRUE.equals(ok);
    }

    /** Gives a claim back when handling failed, so the retry is not mistaken for a duplicate. */
    public void release(String eventId) {
        redis.delete("evt:" + eventId);
    }

    public void marketCreated(String id) {
        redis.opsForSet().add("markets:live", id);
    }

    public void marketResolved(String id) {
        redis.opsForSet().remove("markets:live", id);
        for (String part : List.of("book")) redis.delete(k(id, part));
    }

    public void trade(String marketId, JsonNode t) {
        int price = t.path("price").asInt();
        long shares = t.path("shares").asLong();
        redis.opsForValue().set(k(marketId, "last"), String.valueOf(price));
        redis.opsForSet().add("markets:live", marketId);
        redis.opsForList().leftPush(k(marketId, "trades"), t.toString());
        redis.opsForList().trim(k(marketId, "trades"), 0, TRADES - 1);
        redis.opsForList().rightPush(k(marketId, "spark"), String.valueOf(price));
        redis.opsForList().trim(k(marketId, "spark"), -SPARK, -1);
        redis.opsForValue().increment(k(marketId, "vol"), cash(t));
        redis.opsForSet().add(k(marketId, "traders"), t.path("bidUserId").asString(), t.path("askUserId").asString());

        long epoch = Instant.parse(t.path("executedAt").asString()).getEpochSecond();
        long bucket = Candles.bucket(epoch);
        String field = String.valueOf(bucket);
        Object raw = redis.opsForHash().get(k(marketId, "candles"), field);
        Candles.Candle c = raw == null ? Candles.start(bucket, price, shares)
                : mapper.readValue(raw.toString(), Candles.Candle.class).add(price, shares);
        redis.opsForHash().put(k(marketId, "candles"), field, mapper.writeValueAsString(c));
        Long size = redis.opsForHash().size(k(marketId, "candles"));
        if (size != null && size > CANDLES + 20) {
            List<Long> ts = redis.opsForHash().keys(k(marketId, "candles")).stream().map(o -> Long.parseLong(o.toString())).sorted().toList();
            for (int i = 0; i < ts.size() - CANDLES; i++) redis.opsForHash().delete(k(marketId, "candles"), String.valueOf(ts.get(i)));
        }
    }

    /** Cash that changed hands, matching trading-service's volume. */
    static long cash(JsonNode t) {
        long n = t.path("shares").asLong();
        int p = t.path("price").asInt();
        return switch (t.path("kind").asString("")) {
            case "MINT", "BURN" -> 100L * n;
            case "TRANSFER_NO" -> (long) (100 - p) * n;
            default -> (long) p * n;
        };
    }

    public void book(String marketId, JsonNode book) {
        ObjectNode b = mapper.createObjectNode();
        b.set("bids", book.path("bids"));
        b.set("asks", book.path("asks"));
        redis.opsForValue().set(k(marketId, "book"), b.toString());
    }

    public void news(String marketId, JsonNode headline) {
        redis.opsForList().leftPush(k(marketId, "news"), headline.toString());
        redis.opsForList().trim(k(marketId, "news"), 0, NEWS - 1);
    }

    public void wallet(JsonNode w) {
        String user = w.path("userId").asString();
        redis.opsForZSet().add("lb:profit", user, w.path("profitCents").asDouble());
        redis.opsForValue().set("lb:row:" + user, w.toString());
        String point = Instant.now().getEpochSecond() + ":" + w.path("equityCents").asLong();
        redis.opsForList().rightPush("eq:" + user, point);
        redis.opsForList().trim("eq:" + user, -EQUITY, -1);
    }

    public void seen(String userId) {
        if (userId != null) redis.opsForZSet().add("presence", userId, System.currentTimeMillis());
    }

    // ---------- reads ----------

    public Map<String, Object> market(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marketId", id);
        String last = redis.opsForValue().get(k(id, "last"));
        out.put("lastPrice", last == null ? null : Integer.parseInt(last));
        String book = redis.opsForValue().get(k(id, "book"));
        out.put("book", book == null ? Map.of("bids", List.of(), "asks", List.of()) : mapper.readTree(book));
        List<Candles.Candle> candles = new ArrayList<>();
        for (Object v : redis.opsForHash().values(k(id, "candles"))) candles.add(mapper.readValue(v.toString(), Candles.Candle.class));
        candles.sort(Comparator.comparingLong(Candles.Candle::t));
        out.put("candles", candles);
        out.put("trades", jsonList(k(id, "trades")));
        String vol = redis.opsForValue().get(k(id, "vol"));
        out.put("volumeCents", vol == null ? 0 : Long.parseLong(vol));
        Long traders = redis.opsForSet().size(k(id, "traders"));
        out.put("traderCount", traders == null ? 0 : traders);
        out.put("news", jsonList(k(id, "news")));
        return out;
    }

    public Map<String, Object> markets() {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> ids = redis.opsForSet().members("markets:live");
        if (ids == null) return out;
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            String last = redis.opsForValue().get(k(id, "last"));
            m.put("lastPrice", last == null ? null : Integer.parseInt(last));
            String vol = redis.opsForValue().get(k(id, "vol"));
            m.put("volumeCents", vol == null ? 0 : Long.parseLong(vol));
            Long traders = redis.opsForSet().size(k(id, "traders"));
            m.put("traderCount", traders == null ? 0 : traders);
            List<String> spark = redis.opsForList().range(k(id, "spark"), 0, -1);
            m.put("spark", spark == null ? List.of() : spark.stream().map(Integer::parseInt).toList());
            out.put(id, m);
        }
        return out;
    }

    public Map<String, Object> leaderboard(int limit, String me) {
        Long total = redis.opsForZSet().zCard("lb:profit");
        Set<String> top = redis.opsForZSet().reverseRange("lb:profit", 0, Math.max(0, limit - 1));
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 0;
        if (top != null) for (String u : top) rows.add(row(u, ++rank));
        Map<String, Object> mine = null;
        if (me != null) {
            Long r = redis.opsForZSet().reverseRank("lb:profit", me);
            if (r != null) mine = row(me, r.intValue() + 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("me", mine);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    private Map<String, Object> row(String user, int rank) {
        String json = redis.opsForValue().get("lb:row:" + user);
        JsonNode w = json == null ? mapper.createObjectNode() : mapper.readTree(json);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rank", rank);
        r.put("userId", user);
        r.put("username", w.path("username").asString(""));
        r.put("accountType", w.path("accountType").asString("HUMAN"));
        r.put("profitCents", w.path("profitCents").asLong());
        r.put("equityCents", w.path("equityCents").asLong());
        r.put("marketsTraded", w.path("marketsTraded").asLong());
        r.put("marketsWon", w.path("marketsWon").asLong());
        r.put("marketsResolved", w.path("marketsResolved").asLong());
        return r;
    }

    public List<Map<String, Object>> equity(String user) {
        long since = Instant.now().minusSeconds(3600).getEpochSecond();
        List<String> pts = redis.opsForList().range("eq:" + user, 0, -1);
        List<Map<String, Object>> out = new ArrayList<>();
        if (pts == null) return out;
        for (String p : pts) {
            String[] parts = p.split(":");
            long t = Long.parseLong(parts[0]);
            if (t >= since) out.add(Map.of("t", t, "equityCents", Long.parseLong(parts[1])));
        }
        return out;
    }

    public long tradersOnline() {
        long cutoff = System.currentTimeMillis() - 60_000;
        redis.opsForZSet().removeRangeByScore("presence", 0, cutoff);
        Long n = redis.opsForZSet().count("presence", cutoff, Double.MAX_VALUE);
        return n == null ? 0 : n;
    }

    private List<JsonNode> jsonList(String key) {
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null) return List.of();
        return raw.stream().map(mapper::readTree).toList();
    }

    public static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
