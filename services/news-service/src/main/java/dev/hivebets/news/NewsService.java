package dev.hivebets.news;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventPublisher;
import dev.hivebets.common.events.EventTypes;
import dev.hivebets.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    static final Set<String> SOURCES = Set.of("synthetic", "real", "manual");
    private final MongoTemplate mongo;
    private final EventPublisher publisher;
    private final EventFactory events;
    private final dev.hivebets.common.events.Backoff backoff = new dev.hivebets.common.events.Backoff(200, 10_000);

    public NewsService(MongoTemplate mongo, EventPublisher publisher, EventFactory events) {
        this.mongo = mongo;
        this.publisher = publisher;
        this.events = events;
    }

    public Headline add(String marketId, String text, String source, String url, String publisher) {
        if (text == null || text.isBlank() || text.length() > 400) throw ApiException.validation("A headline of up to 400 characters is required.");
        if (!SOURCES.contains(source)) throw ApiException.validation("source is synthetic, real or manual.");
        if (marketId != null && !marketId.isBlank()) {
            try {
                UUID.fromString(marketId);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("marketId is not a valid id.");
            }
        }
        Headline h = new Headline(UUID.randomUUID().toString(), blankToNull(marketId), text.trim(), source, blankToNull(url),
                blankToNull(publisher), Instant.now(), false, EventFactory.currentTraceparent());
        mongo.insert(h);
        relay();
        return h;
    }

    public List<Headline> list(String marketId, int limit) {
        Query q = new Query();
        if (marketId != null && !marketId.isBlank()) q.addCriteria(Criteria.where("marketId").is(marketId));
        q.with(Sort.by(Sort.Direction.DESC, "publishedAt")).limit(Math.min(Math.max(limit, 1), 200));
        return mongo.find(q, Headline.class);
    }

    /** Publishes unpublished headlines (the outbox), oldest first. Also runs right after each insert. */
    @Scheduled(fixedDelay = 500)
    public synchronized void relay() {
        if (!backoff.ready()) return;
        try {
            List<Headline> pending = mongo.find(new Query(Criteria.where("published").is(false))
                    .with(Sort.by("publishedAt")).limit(100), Headline.class);
            if (pending.isEmpty()) return;
            publisher.publishAll(pending.stream().map(h -> new EventPublisher.Outgoing(EventTypes.NEWS_PUBLISHED,
                    events.toJson(events.envelope(EventTypes.NEWS_PUBLISHED, h.marketId(), h.view())), h.traceparent())).toList());
            mongo.updateMulti(new Query(Criteria.where("_id").in(pending.stream().map(Headline::id).toList())),
                    new Update().set("published", true), Headline.class);
            backoff.succeeded();
        } catch (RuntimeException e) {
            long wait = backoff.failed();
            if (backoff.failures() <= 3 || backoff.failures() % 20 == 0) {
                log.warn("news relay failed ({} in a row), next try in {} ms: {}", backoff.failures(), wait, e.toString());
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
