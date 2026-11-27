package dev.hivebets.realtime;

import dev.hivebets.common.web.ApiException;
import dev.hivebets.common.web.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveController {
    private final LiveState state;
    private final LiveSocket socket;

    public LiveController(LiveState state, LiveSocket socket) {
        this.state = state;
        this.socket = socket;
    }

    @GetMapping("/api/live/markets")
    public Map<String, Object> markets(Optional<CurrentUser> user) {
        user.ifPresent(u -> state.seen(u.id().toString()));
        return state.markets();
    }

    @GetMapping("/api/live/markets/{id}")
    public Map<String, Object> market(@PathVariable String id, Optional<CurrentUser> user) {
        if (!LiveState.isUuid(id)) throw ApiException.notFound("No such market.");
        user.ifPresent(u -> state.seen(u.id().toString()));
        return state.market(id);
    }

    @GetMapping("/api/live/leaderboard")
    public Map<String, Object> leaderboard(@RequestParam(defaultValue = "50") int limit, Optional<CurrentUser> user) {
        return state.leaderboard(Math.min(Math.max(limit, 1), 200), user.map(u -> u.id().toString()).orElse(null));
    }

    @GetMapping("/api/live/equity")
    public List<Map<String, Object>> equity(CurrentUser user) {
        return state.equity(user.id().toString());
    }

    @GetMapping("/api/live/stats")
    public Map<String, Object> stats() {
        return Map.of("tradersOnline", state.tradersOnline(), "connections", socket.connections());
    }
}
