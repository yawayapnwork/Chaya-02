package dev.chaya.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds tokens shaped like Keycloak access tokens, signed with a test-only key pair. */
final class TestJwt {

    static final String ISSUER = "https://keycloak.test/realms/chaya";
    static final String AUDIENCE = "chaya-api";
    static final RSAKey KEY = generate("trusted");
    static final RSAKey OTHER_KEY = generate("untrusted");

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private String subject = "user-" + UUID.randomUUID();
    private String issuer = ISSUER;
    private String audience = AUDIENCE;
    private Duration lifetime = Duration.ofMinutes(5);
    private RSAKey signingKey = KEY;
    private UUID orgId;
    private final List<String> roles = new ArrayList<>();
    private final List<String> venueIds = new ArrayList<>();

    static TestJwt user(UUID orgId, String... roles) {
        TestJwt t = new TestJwt();
        t.orgId = orgId;
        t.roles.addAll(List.of(roles));
        return t;
    }

    static TestJwt service() {
        TestJwt t = new TestJwt();
        t.subject = "service-account-chaya-worker";
        t.roles.add("service");
        return t;
    }

    TestJwt venues(UUID... ids) {
        for (UUID id : ids) {
            venueIds.add(id.toString());
        }
        return this;
    }

    TestJwt role(String role) {
        roles.add(role);
        return this;
    }

    TestJwt withoutOrg() {
        orgId = null;
        return this;
    }

    TestJwt issuer(String v) {
        issuer = v;
        return this;
    }

    TestJwt audience(String v) {
        audience = v;
        return this;
    }

    TestJwt lifetime(Duration v) {
        lifetime = v;
        return this;
    }

    TestJwt signedWith(RSAKey key) {
        signingKey = key;
        return this;
    }

    private JWTClaimsSet claims() {
        Instant now = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
            .subject(subject).issuer(issuer).audience(audience)
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plus(lifetime)))
            .claim("realm_access", Map.of("roles", roles));
        if (orgId != null) {
            c.claim("org_id", orgId.toString());
        }
        if (venueIds.size() == 1) {
            c.claim("venue_id", venueIds.get(0));
        } else if (venueIds.size() > 1) {
            c.claim("venue_id", venueIds);
        }
        return c.build();
    }

    String token() {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An alg=none token with otherwise valid claims. */
    String unsignedToken() {
        return new PlainJWT(claims()).serialize();
    }
}
