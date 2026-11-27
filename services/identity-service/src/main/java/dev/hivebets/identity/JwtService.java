package dev.hivebets.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Signs session tokens with an RSA key kept in Postgres, so restarts keep sessions valid. */
@Service
public class JwtService {
    public static final String ISSUER = "hivebets-identity";
    private final RSAKey key;
    private final Duration ttl;

    public JwtService(JdbcTemplate jdbc, @Value("${hivebets.session.ttl-hours}") int ttlHours) throws Exception {
        this.ttl = Duration.ofHours(ttlHours);
        List<String> stored = jdbc.queryForList(
                "select private_jwk from identity.signing_keys order by created_at desc limit 1", String.class);
        if (!stored.isEmpty()) {
            key = RSAKey.parse(stored.getFirst());
        } else {
            RSAKey generated = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256).generate();
            jdbc.update("insert into identity.signing_keys (kid, private_jwk) values (?, ?) on conflict do nothing",
                    generated.getKeyID(), generated.toJSONString());
            // Another instance may have won the race; use whatever is stored now.
            key = RSAKey.parse(jdbc.queryForObject(
                    "select private_jwk from identity.signing_keys order by created_at desc limit 1", String.class));
        }
    }

    public String issue(User u) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(u.id().toString())
                .claim("name", u.username())
                .claim("roles", u.roles())
                .claim("acct", u.accountType())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public Duration ttl() {
        return ttl;
    }

    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    RSAKey keyForTests() {
        return key;
    }

    static JWTClaimsSet parseUnverified(String token) throws ParseException {
        return SignedJWT.parse(token).getJWTClaimsSet();
    }
}
