package dev.hivebets.news;

import java.util.List;
import java.util.Map;

/**
 * Decides what to do with one embedded headline: drop it as a near-duplicate of something already
 * published, publish it against the most similar open real-world market, or keep it unmatched.
 */
public final class Matcher {
    private Matcher() {}

    public record Decision(String status, String marketId, double similarity) {}

    public static Decision decide(float[] headline, Map<String, float[]> markets, List<float[]> recentPublished,
                                  double matchThreshold, double duplicateThreshold) {
        for (float[] p : recentPublished) {
            double s = Embeddings.cosine(headline, p);
            if (s >= duplicateThreshold) return new Decision("DUPLICATE", null, s);
        }
        String best = null;
        double bestScore = -1;
        for (Map.Entry<String, float[]> m : markets.entrySet()) {
            double s = Embeddings.cosine(headline, m.getValue());
            if (s > bestScore) {
                bestScore = s;
                best = m.getKey();
            }
        }
        if (best != null && bestScore >= matchThreshold) return new Decision("MATCHED", best, bestScore);
        return new Decision("UNMATCHED", best, Math.max(bestScore, 0));
    }
}
