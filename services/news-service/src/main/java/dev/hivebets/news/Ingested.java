package dev.hivebets.news;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One item read from an RSS feed, and what the matcher decided about it. */
@Document("ingested")
public record Ingested(
        @Id String id,
        @Indexed(unique = true) String guid,
        String title,
        String url,
        String publisher,
        String feed,
        @Indexed Instant ingestedAt,
        String status,           // PENDING, MATCHED, UNMATCHED, DUPLICATE, NO_EMBEDDINGS
        Double similarity,
        String matchedMarketId,
        String headlineId,
        float[] embedding) {

    public Ingested with(String status, Double similarity, String marketId, String headlineId, float[] embedding) {
        return new Ingested(id, guid, title, url, publisher, feed, ingestedAt, status, similarity, marketId, headlineId, embedding);
    }

    public record View(String id, String title, String url, String publisher, String feed, Double similarity,
                       String matchedMarketId, String status, String headlineId, Instant ingestedAt) {}

    public View view() {
        return new View(id, title, url, publisher, feed, similarity, matchedMarketId, status, headlineId, ingestedAt);
    }
}
