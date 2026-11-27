package dev.hivebets.identity;

import dev.hivebets.common.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9_-]{3,32}$");
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(1);

    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final String agentInviteCode;
    final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public AuthService(UserRepository users, StringRedisTemplate redis,
                       @Value("${hivebets.agent-invite-code}") String agentInviteCode) {
        this.users = users;
        this.redis = redis;
        this.agentInviteCode = agentInviteCode;
    }

    public User signUp(String username, String password, String inviteCode) {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(name).matches()) {
            throw ApiException.validation("Usernames are 3 to 32 characters: lower-case letters, digits, - and _.");
        }
        if (password == null || password.length() < 8) throw ApiException.validation("Passwords need at least 8 characters.");
        if (password.length() > 128) throw ApiException.validation("Passwords can be at most 128 characters.");
        String type = "HUMAN";
        if (inviteCode != null && !inviteCode.isBlank()) {
            if (!java.security.MessageDigest.isEqual(inviteCode.trim().getBytes(), agentInviteCode.getBytes())) {
                throw ApiException.validation("That invite code is not valid.");
            }
            type = "AGENT";
        }
        return users.insert(name, encoder.encode(password), List.of("TRADER"), type)
                .orElseThrow(() -> ApiException.conflict("CONFLICT", "That username is taken."));
    }

    public User signIn(String username, String password) {
        String name = username == null ? "" : username.trim().toLowerCase();
        String failKey = "login:fail:" + name;
        String fails = redis.opsForValue().get(failKey);
        if (fails != null && Integer.parseInt(fails) >= MAX_FAILURES) {
            throw new ApiException(429, "RATE_LIMITED", "Too many wrong attempts. Try again in a minute.");
        }
        var row = users.findByUsername(name).orElse(null);
        if (row == null || password == null || !encoder.matches(password, row.passwordHash())) {
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1) redis.expire(failKey, LOCK);
            throw new ApiException(401, "UNAUTHENTICATED", "Wrong username or password.");
        }
        redis.delete(failKey);
        return row.user();
    }
}
