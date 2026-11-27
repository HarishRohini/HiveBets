package dev.hivebets.realtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Consumer side of the event contract: realtime-service must handle each schema's example payload,
 * which contains only what the schema guarantees. Also covers idempotency and dead-lettering.
 */
class EventConsumerContractTest {
    static final String MARKET = "5d7e6c1a-6b9f-4d3e-9a51-1f0f6c0c2b11";
    EventFactory factory = new EventFactory(EventSchemas.MAPPER, "test");
    LiveState state;
    Fanout fanout;
    EventConsumer consumer;

    @BeforeEach
    void setUp() {
        state = mock(LiveState.class);
        fanout = mock(Fanout.class);
        when(state.claim(anyString())).thenReturn(true);
        consumer = new EventConsumer(factory, state, fanout);
    }

    Message message(String type) {
        return new Message(factory.toJson(factory.envelope(type, MARKET, EventSchemas.example(type))).getBytes(), new MessageProperties());
    }

    @Test
    void trade() {
        consumer.onEvent(message("trade.executed"));
        verify(state).trade(eq(MARKET), any());
        verify(fanout).publish(eq("market:" + MARKET), eq("trade.executed"), any());
        verify(fanout).publish(eq("markets"), eq("trade.executed"), any());
    }

    @Test
    void book() {
        consumer.onEvent(message("book.changed"));
        verify(state).book(eq(MARKET), any());
        verify(fanout).publish(eq("market:" + MARKET), eq("book.changed"), any());
    }

    @Test
    void marketLifecycle() {
        consumer.onEvent(message("market.created"));
        verify(state).marketCreated(MARKET);
        consumer.onEvent(message("market.closed"));
        verify(fanout).publish(eq("market:" + MARKET), eq("market.closed"), any());
        consumer.onEvent(message("market.resolved"));
        verify(state).marketResolved(MARKET);
    }

    @Test
    void news() {
        consumer.onEvent(message("news.published"));
        verify(state).news(eq(MARKET), any());
        verify(fanout).publish(eq("market:" + MARKET), eq("news.published"), any());
    }

    @Test
    void wallet() {
        consumer.onEvent(message("wallet.changed"));
        verify(state).wallet(any());
        verify(fanout).publish(eq("user:0b1f8a35-7c84-4b0e-a2b6-5e6a3d6d9f01"), eq("wallet.changed"), any());
        verify(fanout).publish(eq("leaderboard"), eq("wallet.changed"), any());
    }

    @Test
    void tradeVolumeUsesOnlySchemaFields() {
        // 10 shares minted at 60: both sides together put in 100 cents a share
        org.junit.jupiter.api.Assertions.assertEquals(1000, LiveState.cash(EventSchemas.example("trade.executed")));
    }

    @Test
    void duplicatesAreSkipped() {
        when(state.claim(anyString())).thenReturn(false);
        consumer.onEvent(message("trade.executed"));
        verify(state, never()).trade(anyString(), any());
    }

    @Test
    void failedHandlingReleasesTheClaimSoTheRetryRuns() {
        doThrow(new IllegalStateException("redis down")).when(state).trade(anyString(), any());
        assertThrows(IllegalStateException.class, () -> consumer.onEvent(message("trade.executed")));
        verify(state).release(anyString());
    }

    @Test
    void malformedMessagesGoStraightToTheDeadLetterQueue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onEvent(new Message("not json".getBytes(), new MessageProperties())));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onEvent(new Message("{\"type\":\"trade.executed\"}".getBytes(), new MessageProperties())));
        verify(state, never()).claim(anyString());
    }
}
