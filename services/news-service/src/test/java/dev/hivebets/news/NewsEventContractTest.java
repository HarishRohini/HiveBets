package dev.hivebets.news;

import dev.hivebets.common.events.EventSchemas;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NewsEventContractTest {
    @Test
    void publishedHeadlinesMatchSchema() {
        var synthetic = new Headline("h1", "5d7e6c1a-6b9f-4d3e-9a51-1f0f6c0c2b11", "Ice miners back the bill", "synthetic",
                null, null, Instant.now(), false, null);
        EventSchemas.assertValid("news.published", synthetic.view());
        var real = new Headline("h2", "5d7e6c1a-6b9f-4d3e-9a51-1f0f6c0c2b11", "Bank of England cuts rates", "real",
                "https://example.org/a", "Example Wire", Instant.now(), false, null);
        EventSchemas.assertValid("news.published", real.view());
    }
}
