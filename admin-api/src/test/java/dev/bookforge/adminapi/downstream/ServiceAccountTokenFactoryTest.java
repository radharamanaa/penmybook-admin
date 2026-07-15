package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the service-account JWT minted on every outbound /internal/* call. Verifies that:
 * <ul>
 *   <li>each callee gets its OWN audience claim (entitlements / billing / org);</li>
 *   <li>iss = {@code bookforge-internal} per contract;</li>
 *   <li>exp = now + 5 min (within tolerance) per contract;</li>
 *   <li>signature actually verifies under the configured key.</li>
 * </ul>
 */
class ServiceAccountTokenFactoryTest {

    private static final String KEY = "dev-only-hs256-shared-secret-change-me";
    private static final String ISSUER = "bookforge-internal";
    private static final String OWN_AUD = "admin-api"; // admin-api's own audience claim of received tokens

    private final ServiceAccountTokenFactory factory = new ServiceAccountTokenFactory(
            new ServiceJwtProperties(KEY, ISSUER, OWN_AUD), new ObjectMapper());

    @Test
    void mintsTokenForEntitlementsWithRightClaimsAndValidSignature() throws Exception {
        assertMintedToken("entitlements");
    }

    @Test
    void mintsTokenForBillingWithRightClaimsAndValidSignature() throws Exception {
        assertMintedToken("billing");
    }

    @Test
    void mintsTokenForOrgWithRightClaimsAndValidSignature() throws Exception {
        assertMintedToken("org");
    }

    private void assertMintedToken(String audience) throws Exception {
        long beforeMintEpoch = Instant.now().getEpochSecond();
        String token = factory.mint(audience);

        String[] parts = token.split("\\.", -1);
        assertThat(parts).as("JWT has three dot-separated parts").hasSize(3);

        Base64.Decoder dec = Base64.getUrlDecoder();
        JsonNode header = new ObjectMapper().readTree(dec.decode(parts[0]));
        JsonNode claims = new ObjectMapper().readTree(dec.decode(parts[1]));

        assertThat(header.path("alg").asText()).isEqualTo("HS256");
        assertThat(header.path("typ").asText()).isEqualTo("JWT");

        assertThat(claims.path("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.path("aud").asText())
                .as("aud must be the callee, not admin-api's own audience")
                .isEqualTo(audience);
        assertThat(claims.path("scope").asText()).isEqualTo("internal");

        long exp = claims.path("exp").asLong();
        long expected = beforeMintEpoch + 300L;
        assertThat(exp)
                .as("exp must be ~now+5min")
                .isBetween(expected - 5, expected + 5);

        // Signature actually verifies under the configured key.
        String signingInput = parts[0] + "." + parts[1];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedSig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2])
                .as("signature must verify under the configured key")
                .isEqualTo(expectedSig);
    }
}
