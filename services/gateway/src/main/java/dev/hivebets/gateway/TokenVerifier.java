package dev.hivebets.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Verifies RS256 session tokens against identity-service's JWKS (cached and refreshed by Nimbus). */
@Component
public class TokenVerifier {
    public record Principal(String id, String username, Set<String> roles, String accountType) {}

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public TokenVerifier(@Value("${hivebets.jwks-url}") String jwksUrl) throws Exception {
        JWKSource<SecurityContext> source = JWKSourceBuilder.create(URI.create(jwksUrl).toURL())
                .retrying(true).build();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer("hivebets-identity").build(), Set.of("sub", "exp", "name")));
    }

    /** Empty when the token is missing, expired or forged. */
    public Mono<Principal> verify(String token) {
        if (token == null || token.isBlank()) return Mono.empty();
        return Mono.fromCallable(() -> {
                    JWTClaimsSet c = processor.process(token, null);
                    List<String> roles = c.getStringListClaim("roles");
                    return new Principal(c.getSubject(), c.getStringClaim("name"),
                            roles == null ? Set.of() : new HashSet<>(roles), c.getStringClaim("acct"));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.empty());
    }
}
