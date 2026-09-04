package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints the short-lived HS256 service-account JWT that authenticates every outbound
 * {@code /internal/*} call. Claims per contract: {@code iss=bookforge-internal},
 * {@code aud=<callee>}, {@code exp=now+5min}, {@code scope=internal}. The {@code aud} must match
 * the callee, so a token is minted per target service.
 */
@Component
public class ServiceAccountTokenFactory {

    private static final long TTL_SECONDS = 300L; // now + 5 min, per contract
    private static final int HS256_MIN_KEY_BYTES = 32; // 256 bits — JWA minimum for HS256
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ServiceJwtProperties properties;
    private final ObjectMapper objectMapper;

    public ServiceAccountTokenFactory(ServiceJwtProperties properties, ObjectMapper objectMapper) {
        int keyLen = properties.signingKey().getBytes(StandardCharsets.UTF_8).length;
        if (keyLen < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "SERVICE_JWT_SIGNING_KEY must be at least "
                            + HS256_MIN_KEY_BYTES
                            + " bytes (256 bits) for HS256; got "
                            + keyLen
                            + " bytes");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Matches InternalController.ADMIN_API_SUBJECT in entitlements. */
    static final String SERVICE_SUBJECT = "service:admin_api";

    /** @param targetAudience the callee service: entitlements | billing | org. */
    public String mint(String targetAudience) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", properties.issuer());
            // Caller identity for entitlements' caller-allowlisted routes (pre-admit). Every other
            // mesh verifier treats sub as optional, so stamping it on every token is safe.
            claims.put("sub", SERVICE_SUBJECT);
            claims.put("aud", targetAudience);
            claims.put("exp", now + TTL_SECONDS);
            claims.put("scope", "internal");

            String encodedHeader = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
            String encodedClaims = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            String signature = URL_ENCODER.encodeToString(sign(signingInput));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint service-account JWT", e);
        }
    }

    private byte[] sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }
}
