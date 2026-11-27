package dev.hivebets.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.hivebets.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AuthServiceTest {
    UserRepository users;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    AuthService auth;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        auth = new AuthService(users, redis, "secret-invite");
        when(users.insert(anyString(), anyString(), any(), anyString())).thenAnswer(inv ->
                Optional.of(new User(UUID.randomUUID(), inv.getArgument(0), inv.getArgument(2), inv.getArgument(3), Instant.now())));
    }

    @Test
    void humanSignUpLowercasesTheUsername() {
        User u = auth.signUp("  Alice ", "long-enough", null);
        assertEquals("alice", u.username());
        assertEquals("HUMAN", u.accountType());
        assertEquals(List.of("TRADER"), u.roles());
    }

    @Test
    void inviteCodeMakesAnAgent() {
        assertEquals("AGENT", auth.signUp("news-hawk-0291", "long-enough", "secret-invite").accountType());
    }

    @Test
    void wrongInviteCodeIsRejected() {
        var e = assertThrows(ApiException.class, () -> auth.signUp("bob", "long-enough", "guess"));
        assertEquals("VALIDATION", e.code());
    }

    @Test
    void weakInputIsRejected() {
        assertThrows(ApiException.class, () -> auth.signUp("bob", "short", null));
        assertThrows(ApiException.class, () -> auth.signUp("b!", "long-enough", null));
    }

    @Test
    void takenUsernameIsAConflict() {
        when(users.insert(anyString(), anyString(), any(), anyString())).thenReturn(Optional.empty());
        assertEquals(409, assertThrows(ApiException.class, () -> auth.signUp("bob", "long-enough", null)).status());
    }

    @Test
    void wrongPasswordCountsAFailure() {
        String hash = auth.encoder.encode("right-password");
        when(users.findByUsername("bob")).thenReturn(Optional.of(new UserRepository.Row(
                new User(UUID.randomUUID(), "bob", List.of("TRADER"), "HUMAN", Instant.now()), hash)));
        when(values.increment("login:fail:bob")).thenReturn(1L);
        assertEquals(401, assertThrows(ApiException.class, () -> auth.signIn("bob", "nope")).status());
        verify(redis).expire(eq("login:fail:bob"), any(java.time.Duration.class));
        assertEquals("bob", auth.signIn("bob", "right-password").username());
        verify(redis).delete("login:fail:bob");
    }

    @Test
    void lockedUsernameIsRefusedBeforeCheckingThePassword() {
        when(values.get("login:fail:bob")).thenReturn("5");
        assertEquals(429, assertThrows(ApiException.class, () -> auth.signIn("bob", "anything")).status());
        verify(users, never()).findByUsername(anyString());
    }
}
