package dev.hivebets.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class AuthRulesTest {
    @Test
    void usernameRules() {
        assertTrue(AuthService.USERNAME.matcher("quiet-quant-0502").matches());
        assertTrue(AuthService.USERNAME.matcher("bob_1").matches());
        assertFalse(AuthService.USERNAME.matcher("ab").matches());
        assertFalse(AuthService.USERNAME.matcher("Bob").matches());
        assertFalse(AuthService.USERNAME.matcher("bob smith").matches());
    }

    @Test
    void argon2RoundTrip() {
        var enc = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String hash = enc.encode("correct horse");
        assertTrue(hash.startsWith("$argon2id$"));
        assertTrue(enc.matches("correct horse", hash));
        assertFalse(enc.matches("wrong", hash));
    }
}
