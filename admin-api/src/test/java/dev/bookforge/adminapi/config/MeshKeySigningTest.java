package dev.bookforge.adminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.bookforge.adminapi.downstream.ServiceAccountTokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H1 — admin-api signs its mesh tokens with MESH_KEY_ADMIN_API once set (docs/security/mesh-keys.md);
 * S3/S4 — outside the dev profile a missing or development admin-login or mesh key stops startup.
 */
class MeshKeySigningTest {

    private static final String SHARED = "shared-mesh-key-at-least-32-bytes-long!!";
    private static final String OWN_KEY = "admin-api-own-key-at-least-32-bytes-long";
    private static final String REAL_ADMIN_SECRET = "a-real-admin-login-secret-at-least-32-bytes";

    private static ServiceJwtProperties properties(String sharedKey, String ownKey) {
        return new ServiceJwtProperties(sharedKey, "bookforge-internal", "admin-api", "admin_api", ownKey,
                List.of(), Map.of());
    }

    private static SignedJWT mint(String ownKey) throws Exception {
        return SignedJWT.parse(new ServiceAccountTokenFactory(properties(SHARED, ownKey), new ObjectMapper())
                .mint("billing"));
    }

    @Test
    void signsWithTheSharedKeyUntilItsOwnKeyIsSet() throws Exception {
        for (String own : new String[] {"", "REPLACE_ME"}) {
            SignedJWT jwt = mint(own);
            assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("service:admin_api");
            assertThat(jwt.verify(new MACVerifier(SHARED.getBytes(StandardCharsets.UTF_8)))).isTrue();
        }
    }

    @Test
    void signsWithItsOwnKeyOnceSet() throws Exception {
        // Fails on the old code, which always signed with the shared key.
        SignedJWT jwt = mint(OWN_KEY);
        assertThat(jwt.verify(new MACVerifier(OWN_KEY.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.verify(new MACVerifier(SHARED.getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    @Test
    void outsideDevProfileADevAdminLoginSecretStopsStartup() {
        AdminJwtProperties devAdmin = new AdminJwtProperties(
                "dev-insecure-admin-secret-change-me-32b!", "book_admin", "bookforge-admin");
        // Fails on the old code: admin-api started on the dev default without any profile.
        assertThatThrownBy(() -> new SigningKeyStartupGuard(new MockEnvironment(), properties(SHARED, ""), devAdmin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_JWT_SECRET");
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        new SigningKeyStartupGuard(dev, properties(SHARED, ""), devAdmin);
    }

    @Test
    void outsideDevProfileADevMeshKeyStopsStartup() {
        AdminJwtProperties realAdmin = new AdminJwtProperties(REAL_ADMIN_SECRET, "book_admin", "bookforge-admin");
        assertThatThrownBy(() -> new SigningKeyStartupGuard(new MockEnvironment(),
                properties("integration-suite-hs256-shared-key-32+bytes", ""), realAdmin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_SIGNING_KEY");
        assertThatThrownBy(() -> new SigningKeyStartupGuard(new MockEnvironment(),
                properties(SHARED, "dev-only-hs256-shared-secret-change-me"), realAdmin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MESH_KEY_ADMIN_API");
        new SigningKeyStartupGuard(new MockEnvironment(), properties(SHARED, OWN_KEY), realAdmin);
    }
}
