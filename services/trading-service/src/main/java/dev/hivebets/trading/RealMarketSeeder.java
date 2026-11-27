package dev.hivebets.trading;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Loads the curated real-world markets (seed/real-markets.json) once; admins resolve them by hand. */
@Component
public class RealMarketSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RealMarketSeeder.class);
    private final TradingService trading;
    private final ObjectMapper mapper;
    private final boolean enabled;

    record Seed(String slug, String question, String yesStatement, String noStatement, Instant closeAt) {}

    public RealMarketSeeder(TradingService trading, ObjectMapper mapper, @Value("${hivebets.seed-real-markets}") boolean enabled) {
        this.trading = trading;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        try (InputStream in = new ClassPathResource("seed/real-markets.json").getInputStream()) {
            List<Seed> seeds = mapper.readValue(in, new TypeReference<>() {});
            int added = 0;
            for (Seed s : seeds) {
                if (s.closeAt().isBefore(Instant.now().plusSeconds(60))) continue;
                var m = trading.create(null, new TradingService.CreateMarket(s.question(), s.yesStatement(), s.noStatement(),
                        "REAL", s.closeAt(), null), s.slug());
                if (m != null) added++;
            }
            if (added > 0) log.info("seeded {} real-world markets", added);
        }
    }
}
