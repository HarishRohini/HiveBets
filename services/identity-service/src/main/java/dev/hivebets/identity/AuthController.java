package dev.hivebets.identity;

import dev.hivebets.common.web.ApiException;
import dev.hivebets.common.web.CurrentUser;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;
    private final JwtService jwt;
    private final UserRepository users;
    private final String cookieName;
    private final boolean secureCookie;

    public AuthController(AuthService auth, JwtService jwt, UserRepository users,
                          @Value("${hivebets.session.cookie-name}") String cookieName,
                          @Value("${hivebets.session.secure-cookie}") boolean secureCookie) {
        this.auth = auth;
        this.jwt = jwt;
        this.users = users;
        this.cookieName = cookieName;
        this.secureCookie = secureCookie;
    }

    public record SignUpRequest(String username, String password, String inviteCode) {}
    public record SignInRequest(String username, String password) {}
    public record SessionResponse(User user, String token) {}

    @PostMapping("/api/auth/signup")
    public ResponseEntity<SessionResponse> signUp(@RequestBody SignUpRequest req) {
        User u = auth.signUp(req.username(), req.password(), req.inviteCode());
        return session(HttpStatus.CREATED, u);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<SessionResponse> signIn(@RequestBody SignInRequest req) {
        User u = auth.signIn(req.username(), req.password());
        return session(HttpStatus.OK, u);
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie c = ResponseCookie.from(cookieName, "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, c.toString()).build();
    }

    @GetMapping("/api/auth/me")
    public User me(CurrentUser user) {
        return users.findById(user.id()).orElseThrow(ApiException::unauthenticated);
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwt.jwks();
    }

    private ResponseEntity<SessionResponse> session(HttpStatus status, User u) {
        String token = jwt.issue(u);
        ResponseCookie c = ResponseCookie.from(cookieName, token).httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(jwt.ttl()).build();
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, c.toString()).body(new SessionResponse(u, token));
    }
}
