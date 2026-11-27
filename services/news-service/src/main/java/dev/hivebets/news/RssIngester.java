package dev.hivebets.news;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Phase 2 real news: polls RSS feeds, de-duplicates by GUID/URL and by embedding similarity, and
 * publishes headlines that are close enough to an open real-world market.
 */
@Service
public class RssIngester {
    private static final Logger log = LoggerFactory.getLogger(RssIngester.class);
    private final MongoTemplate mongo;
    private final NewsService news;
    private final Embeddings embeddings;
    private final RestClient http;
    private final RestClient trading;
    private final List<String> feeds;
    private final double matchThreshold, duplicateThreshold;
    private final Map<String, float[]> marketVectors = new ConcurrentHashMap<>();

    private final tools.jackson.databind.ObjectMapper mapper;

    public RssIngester(MongoTemplate mongo, NewsService news, Embeddings embeddings, tools.jackson.databind.ObjectMapper mapper,
                       @Value("${hivebets.rss.feeds}") String feeds,
                       @Value("${hivebets.trading-url}") String tradingUrl,
                       @Value("${hivebets.embeddings.match-threshold}") double matchThreshold,
                       @Value("${hivebets.embeddings.duplicate-threshold}") double duplicateThreshold) {
        this.mongo = mongo;
        this.news = news;
        this.embeddings = embeddings;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(f)
                .defaultHeader("User-Agent", "HiveBets-news/0.1 (RSS reader)").build();
        this.trading = RestClient.builder().baseUrl(tradingUrl).requestFactory(f).build();
        this.feeds = Arrays.stream(feeds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.matchThreshold = matchThreshold;
        this.duplicateThreshold = duplicateThreshold;
    }

    @Scheduled(fixedDelayString = "${hivebets.rss.poll-ms}", initialDelay = 20_000)
    public void scheduled() {
        if (!feeds.isEmpty()) poll();
    }

    public record PollResult(int fetched, int added, Map<String, Integer> outcomes) {}

    public synchronized PollResult poll() {
        int fetched = 0, added = 0;
        for (String feed : feeds) {
            try {
                byte[] body = http.get().uri(feed).retrieve().body(byte[].class);
                SyndFeed parsed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(body)));
                String publisher = parsed.getTitle() == null ? feed : parsed.getTitle().trim();
                for (SyndEntry e : parsed.getEntries()) {
                    fetched++;
                    String title = e.getTitle() == null ? null : e.getTitle().trim();
                    if (title == null || title.isEmpty()) continue;
                    String guid = e.getUri() != null && !e.getUri().isBlank() ? e.getUri() : e.getLink();
                    if (guid == null) guid = title;
                    try {
                        mongo.insert(new Ingested(UUID.randomUUID().toString(), guid, truncate(title, 400), e.getLink(),
                                publisher, feed, Instant.now(), "PENDING", null, null, null, null));
                        added++;
                    } catch (DuplicateKeyException dup) {
                        // already seen this item
                    }
                }
            } catch (Exception ex) {
                log.warn("could not read feed {}: {}", feed, ex.toString());
            }
        }
        Map<String, Integer> outcomes = process();
        if (added > 0 || !outcomes.isEmpty()) log.info("rss poll: fetched {}, new {}, outcomes {}", fetched, added, outcomes);
        return new PollResult(fetched, added, outcomes);
    }

    /** Matches PENDING items, and retries recent NO_EMBEDDINGS ones (the model server may have started since). */
    Map<String, Integer> process() {
        List<Ingested> todo = mongo.find(new Query(new Criteria().orOperator(
                        Criteria.where("status").is("PENDING"),
                        Criteria.where("status").is("NO_EMBEDDINGS").and("ingestedAt").gte(Instant.now().minus(Duration.ofHours(24)))))
                .with(Sort.by("ingestedAt")).limit(500), Ingested.class);
        Map<String, Integer> outcomes = new HashMap<>();
        if (todo.isEmpty()) return outcomes;

        Map<String, float[]> markets;
        List<float[]> vectors;
        try {
            markets = openRealMarkets();
            vectors = embeddings.documents(todo.stream().map(Ingested::title).toList());
        } catch (Exception ex) {
            log.warn("embedding server unavailable, {} headlines wait: {}", todo.size(), ex.toString());
            for (Ingested i : todo) {
                if (!"NO_EMBEDDINGS".equals(i.status())) mongo.save(i.with("NO_EMBEDDINGS", null, null, null, null));
            }
            outcomes.put("NO_EMBEDDINGS", todo.size());
            return outcomes;
        }
        List<float[]> published = new ArrayList<>(mongo.find(new Query(Criteria.where("status").is("MATCHED")
                                .and("ingestedAt").gte(Instant.now().minus(Duration.ofDays(7))))
                        .with(Sort.by(Sort.Direction.DESC, "ingestedAt")).limit(500), Ingested.class)
                .stream().map(Ingested::embedding).filter(v -> v != null).toList());

        for (int i = 0; i < todo.size(); i++) {
            Ingested item = todo.get(i);
            float[] v = vectors.get(i);
            Matcher.Decision d = Matcher.decide(v, markets, published, matchThreshold, duplicateThreshold);
            String headlineId = null;
            if (d.status().equals("MATCHED")) {
                headlineId = news.add(d.marketId(), item.title(), "real", item.url(), item.publisher()).id();
                published.add(v);
            }
            mongo.save(item.with(d.status(), round(d.similarity()), d.marketId(), headlineId, v));
            outcomes.merge(d.status(), 1, Integer::sum);
        }
        return outcomes;
    }

    /** Open REAL markets with their question embedded as a search query; vectors are cached per market. */
    private Map<String, float[]> openRealMarkets() {
        JsonNode list = mapper.readTree(trading.get().uri("/api/markets?status=OPEN&kind=REAL")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON).retrieve().body(String.class));
        Map<String, String> texts = new HashMap<>();
        for (JsonNode m : list) texts.put(m.path("id").asString(), m.path("question").asString() + " " + m.path("yesStatement").asString(""));
        marketVectors.keySet().retainAll(texts.keySet());
        List<String> missing = texts.keySet().stream().filter(id -> !marketVectors.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            List<float[]> v = embeddings.queries(missing.stream().map(texts::get).toList());
            for (int i = 0; i < missing.size(); i++) marketVectors.put(missing.get(i), v.get(i));
        }
        return Map.copyOf(marketVectors);
    }

    public List<Ingested.View> recent(int limit) {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "ingestedAt")).limit(Math.min(Math.max(limit, 1), 500)),
                Ingested.class).stream().map(Ingested::view).toList();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static Double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }
}
