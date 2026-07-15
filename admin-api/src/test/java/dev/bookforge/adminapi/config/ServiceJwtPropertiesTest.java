package dev.bookforge.adminapi.config;

import dev.bookforge.adminapi.downstream.ServiceAccountTokenFactory;
import dev.bookforge.adminapi.service.StepUpVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins a known GAP in admin-api configuration: a {@code SERVICE_JWT_SIGNING_KEY} shorter than the
 * 256 bits HS256 requires is silently accepted today. HS256 with a key shorter than the digest
 * length (32 bytes) is below the JWA recommended minimum and a real security risk in production.
 *
 * <p>This test EXPECTS to FAIL ("expected RED") until admin-api enforces a minimum key length at
 * startup or at first use of {@link ServiceAccountTokenFactory} / {@link StepUpVerifier}. When the
 * fix lands, instantiating either component with an undersized key MUST throw loudly. The fix
 * makes this test go green; the test is the proof the gap was closed.
 */
class ServiceJwtPropertiesTest {

    @Test
    void undersizedSigningKeyMustBeRejectedLoudly() {
        // 16 chars / 16 bytes UTF-8 — well below the 32 bytes (256 bits) required for HS256.
        String tooShort = "short-dev-key!!"; // 15 bytes
        ServiceJwtProperties props = new ServiceJwtProperties(tooShort, "bookforge-internal", "admin-api");

        // EITHER constructing the verifier OR constructing the token factory MUST loudly reject
        // the undersized key (IllegalArgumentException / IllegalStateException). Today both
        // silently accept it — so this assertion fails RED, pinning the open gap.
        assertThatThrownBy(() -> {
            new StepUpVerifier(props);
            new ServiceAccountTokenFactory(props, new com.fasterxml.jackson.databind.ObjectMapper());
        })
                .as("an HS256 key shorter than 256 bits MUST be rejected at construction/startup")
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }
}
