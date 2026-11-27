package dev.hivebets.news;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatcherTest {
    static float[] v(float... xs) { return Embeddings.normalise(xs); }

    @Test
    void matchesTheClosestMarketAboveThreshold() {
        var markets = Map.of("rates", v(1, 0, 0), "space", v(0, 1, 0));
        var d = Matcher.decide(v(0.9f, 0.1f, 0), markets, List.of(), 0.5, 0.92);
        assertEquals("MATCHED", d.status());
        assertEquals("rates", d.marketId());
    }

    @Test
    void keepsUnrelatedHeadlinesUnmatched() {
        var d = Matcher.decide(v(0, 0, 1), Map.of("rates", v(1, 0, 0)), List.of(), 0.5, 0.92);
        assertEquals("UNMATCHED", d.status());
    }

    @Test
    void dropsNearDuplicates() {
        var d = Matcher.decide(v(1, 0.01f, 0), Map.of("rates", v(1, 0, 0)), List.of(v(1, 0, 0)), 0.5, 0.92);
        assertEquals("DUPLICATE", d.status());
    }

    @Test
    void cosineOfNormalisedVectors() {
        assertEquals(1.0, Embeddings.cosine(v(3, 4), v(3, 4)), 1e-6);
        assertEquals(0.0, Embeddings.cosine(v(1, 0), v(0, 1)), 1e-6);
    }
}
