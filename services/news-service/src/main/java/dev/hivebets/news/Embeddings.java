package dev.hivebets.news;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Client for the llama.cpp embedding server (EmbeddingGemma, OpenAI-compatible /v1/embeddings).
 * EmbeddingGemma expects a task prefix: queries ("what is this market about") and documents
 * (headlines) are embedded differently.
 */
@Component
public class Embeddings {
    private static final Logger log = LoggerFactory.getLogger(Embeddings.class);
    static final String QUERY_PREFIX = "task: search result | query: ";
    static final String DOCUMENT_PREFIX = "title: none | text: ";

    private final RestClient http;
    private final String model;
    private final ObjectMapper mapper;

    public Embeddings(@Value("${hivebets.embeddings.url}") String baseUrl, @Value("${hivebets.embeddings.model}") String model,
                      ObjectMapper mapper) {
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.model = model;
    }

    public List<float[]> queries(List<String> texts) {
        return embed(texts.stream().map(t -> QUERY_PREFIX + t).toList());
    }

    public List<float[]> documents(List<String> texts) {
        return embed(texts.stream().map(t -> DOCUMENT_PREFIX + t).toList());
    }

    /** Throws if the server is down; callers treat that as "no embeddings yet". */
    List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) return List.of();
        String raw = http.post().uri("/v1/embeddings").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(Map.of("model", model, "input", inputs)))
                .retrieve().body(String.class);
        JsonNode res = mapper.readTree(raw);
        List<float[]> out = new ArrayList<>();
        for (JsonNode d : res.path("data")) {
            JsonNode e = d.path("embedding");
            float[] v = new float[e.size()];
            for (int i = 0; i < v.length; i++) v[i] = (float) e.get(i).asDouble();
            out.add(normalise(v));
        }
        if (out.size() != inputs.size()) throw new IllegalStateException("embedding server returned " + out.size() + " of " + inputs.size());
        log.debug("embedded {} texts", out.size());
        return out;
    }

    static float[] normalise(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
        return v;
    }

    /** Cosine similarity of two normalised vectors. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
