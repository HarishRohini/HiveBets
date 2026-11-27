package dev.hivebets.news;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A headline shown to traders. {@code published} doubles as the outbox flag: the document and its
 * pending event are written in one atomic insert, and the relay flips it after the broker confirms.
 */
@Document("headlines")
@CompoundIndex(name = "market_time", def = "{'marketId': 1, 'publishedAt': -1}")
public record Headline(
        @Id String id,
        String marketId,
        String text,
        String source,
        String url,
        String publisher,
        Instant publishedAt,
        @Indexed boolean published,
        String traceparent) {

    public record View(String id, String marketId, String text, String source, String url, String publisher, Instant publishedAt) {}

    public View view() {
        return new View(id, marketId, text, source, url, publisher, publishedAt);
    }
}
