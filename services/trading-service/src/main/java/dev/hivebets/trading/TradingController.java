package dev.hivebets.trading;

import dev.hivebets.common.web.ApiException;
import dev.hivebets.common.web.CurrentUser;
import dev.hivebets.trading.domain.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradingController {
    private final TradingService trading;
    private final Views views;

    public TradingController(TradingService trading, Views views) {
        this.trading = trading;
        this.views = views;
    }

    @GetMapping("/api/markets")
    public List<Views.Market> markets(@RequestParam(required = false) String status, @RequestParam(required = false) String kind) {
        return views.markets(status, kind);
    }

    @GetMapping("/api/markets/{id}")
    public Views.Market market(@PathVariable UUID id) {
        Views.Market m = views.market(id);
        if (m == null) throw ApiException.notFound("No such market.");
        return m;
    }

    @PostMapping("/api/markets")
    public ResponseEntity<Views.Market> create(CurrentUser user, @RequestBody TradingService.CreateMarket req) {
        user.require("ADMIN", "ENGINE");
        return ResponseEntity.status(HttpStatus.CREATED).body(trading.create(user, req, null));
    }

    public record ResolveRequest(Types.Side outcome) {}

    @PostMapping("/api/markets/{id}/resolve")
    public Views.Market resolve(CurrentUser user, @PathVariable UUID id, @RequestBody ResolveRequest req) {
        user.require("ADMIN", "ENGINE");
        return trading.resolve(id, req.outcome());
    }

    @GetMapping("/api/markets/{id}/book")
    public Map<String, Object> book(@PathVariable UUID id) {
        return trading.book(id);
    }

    @GetMapping("/api/markets/{id}/trades")
    public List<Views.Trade> trades(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return views.trades(id, limit);
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Views.Order> place(CurrentUser user, @RequestBody TradingService.PlaceRequest req,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        user.require("TRADER", "ADMIN");
        TradingService.Placed p = trading.place(user, req, key);
        return ResponseEntity.status(p.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(p.order());
    }

    @DeleteMapping("/api/orders/{id}")
    public Views.Order cancel(CurrentUser user, @PathVariable UUID id) {
        return trading.cancel(user, id);
    }

    @GetMapping("/api/orders")
    public List<Views.Order> orders(CurrentUser user, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) UUID marketId) {
        return views.orders(user.id(), status, marketId);
    }

    @GetMapping("/api/portfolio")
    public Map<String, Object> portfolio(CurrentUser user) {
        Map<String, Object> wallet = views.walletSummary(user.id());
        if (wallet == null) {
            wallet = new LinkedHashMap<>();
            wallet.put("availableCents", dev.hivebets.trading.domain.Exchange.STARTING_CENTS);
            wallet.put("reservedCents", 0);
            wallet.put("equityCents", dev.hivebets.trading.domain.Exchange.STARTING_CENTS);
            wallet.put("profitCents", 0);
            wallet.put("startingCents", dev.hivebets.trading.domain.Exchange.STARTING_CENTS);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet", wallet);
        out.put("positions", views.openPositions(user.id()));
        out.put("resolved", views.resolvedPositions(user.id()));
        return out;
    }
}
