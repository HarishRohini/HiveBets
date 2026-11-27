package dev.hivebets.news;

import dev.hivebets.common.web.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NewsController {
    private final NewsService news;

    public NewsController(NewsService news) {
        this.news = news;
    }

    public record PostHeadline(String marketId, String text, String source, String url, String publisher) {}

    @GetMapping("/api/news")
    public List<Headline.View> list(@RequestParam(required = false) String marketId, @RequestParam(defaultValue = "50") int limit) {
        return news.list(marketId, limit).stream().map(Headline::view).toList();
    }

    @PostMapping("/api/news")
    public ResponseEntity<Headline.View> post(CurrentUser user, @RequestBody PostHeadline req) {
        user.require("ADMIN", "ENGINE");
        String source = req.source() != null ? req.source() : user.has("ENGINE") ? "synthetic" : "manual";
        Headline h = news.add(req.marketId(), req.text(), source, req.url(), req.publisher());
        return ResponseEntity.status(HttpStatus.CREATED).body(h.view());
    }
}
