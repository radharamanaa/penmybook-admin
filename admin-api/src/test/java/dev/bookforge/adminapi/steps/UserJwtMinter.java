package dev.bookforge.adminapi.steps;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test-scope helper that mints the admin-login HS256 JWT (the kind admin-api's SecurityConfig
 * validates via {@code NimbusJwtDecoder} after the D4/D16 auth swap). Claims follow
 * docs/admin-control-plane/ARCHITECTURE.md:
 * <ul>
 *   <li>{@code iss=book_admin}</li>
 *   <li>{@code aud=bookforge-admin}</li>
 *   <li>{@code sub=&lt;admin uuid&gt;} (D16 — parses as the audit actor)</li>
 *   <li>{@code role=admin|viewer} — {@code admin} drives the {@code ROLE_PLATFORM_ADMIN}
 *       authority via {@code AdminRoleAuthoritiesConverter}.</li>
 *   <li>{@code email}, {@code iat}, {@code exp}</li>
 * </ul>
 *
 * <p>Signed with the same dev secret the production code defaults to in
 * {@code admin-jwt.secret} ({@code ADMIN_JWT_SECRET}) — deliberately distinct from the mesh
 * {@code SERVICE_JWT_SIGNING_KEY}: a mesh service token must NOT open the admin surface.
 */
public final class UserJwtMinter {

    /** Matches the {@code admin-jwt.secret} default in application.yml / book_admin. */
    public static final String SIGNING_KEY = "dev-insecure-admin-secret-change-me-32b!";
    private static final long TTL_SECONDS = 3600L;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UserJwtMinter() {
    }

    /** Mint an admin-login JWT (role=admin). The {@code sub} is the staff admin's UUID. */
    public static String forPlatformAdmin(UUID staffUserId) {
        return mint(staffUserId, "admin");
    }

    /** Mint a token WITHOUT the admin role — authenticated but unauthorised. */
    public static String forRegularUser(UUID userId) {
        return mint(userId, "viewer");
    }

    private static String mint(UUID userId, String role) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", "book_admin");
            claims.put("aud", "bookforge-admin");
            claims.put("sub", userId.toString());
            claims.put("email", "ops@test");
            claims.put("role", role);
            claims.put("iat", now);
            claims.put("exp", now + TTL_SECONDS);

            String encodedHeader = URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(header));
            String encodedClaims = URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            String signature = URL_ENCODER.encodeToString(sign(signingInput));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("test could not mint admin JWT", e);
        }
    }

    private static byte[] sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }
}
