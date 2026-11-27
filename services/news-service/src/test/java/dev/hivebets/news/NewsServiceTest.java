package dev.hivebets.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventPublisher;
import dev.hivebets.common.events.EventSchemas;
import dev.hivebets.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class NewsServiceTest {
    MongoTemplate mongo = mock(MongoTemplate.class);
    NewsService news = new NewsService(mongo, mock(EventPublisher.class), new EventFactory(EventSchemas.MAPPER, "news-service"));

    @Test
    void storesUnpublishedSoTheRelayPublishesIt() {
        Headline h = news.add("5d7e6c1a-6b9f-4d3e-9a51-1f0f6c0c2b11", "  Ice miners back the bill  ", "synthetic", null, null);
        assertEquals("Ice miners back the bill", h.text());
        assertFalse(h.published());
        verify(mongo).insert(any(Headline.class));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(ApiException.class, () -> news.add(null, " ", "manual", null, null));
        assertThrows(ApiException.class, () -> news.add(null, "x".repeat(401), "manual", null, null));
        assertThrows(ApiException.class, () -> news.add(null, "fine", "rumour", null, null));
        assertThrows(ApiException.class, () -> news.add("not-a-uuid", "fine", "manual", null, null));
    }
}
