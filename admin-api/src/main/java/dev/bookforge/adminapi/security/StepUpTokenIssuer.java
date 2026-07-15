package dev.bookforge.adminapi.security;

import dev.bookforge.adminapi.config.ServiceJwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints the action-bound, params-bound, single-use step-up token that {@link
 * dev.bookforge.adminapi.service.StepUpVerifier} verifies. Admin-api is its OWN step-up gate: once
 * the staff admin's OTP code has been checked (by entitlements), this issuer mints the token bound
 * over the canonical {@code paramsHash} that {@link AdminParamsHasher} produces — NOT the
 * entitlements params-hash scheme — so the verify side recomputes it byte-for-byte.
 *
 * <p>Wire form (identical to the verifier's expectation, same HMAC-SHA256 under the shared
 * {@code SERVICE_JWT_SIGNING_KEY}):
 * <pre>{@code challengeId | action | paramsHash | expiresAtEpoch | base64url(HMAC-SHA256(payload))}</pre>
 *
 * <p>The {@code challengeId} is reused from the entitlements OTP challenge so the token's jti is
 * traceable back to the verified code, and so the downstream {@code stepUpChallengeId} the gated
 * write forwards is a real challenge UUID.
 */
@Component
public class StepUpTokenIssuer {

    /** TTL of a minted step-up token — long enough for the admin to complete the write, short
     * enough to bound the replay window. Matches the mesh-standard 5-minute step-up TTL. */
    private static final long TOKEN_TTL_SECONDS = 300L;
    private static final String SEPARATOR = "|";
    private static final int HS256_MIN_KEY_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] signingKey;

    public StepUpTokenIssuer(ServiceJwtProperties properties) {
        byte[] keyBytes = properties.signingKey().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "SERVICE_JWT_SIGNING_KEY must be at least " + HS256_MIN_KEY_BYTES
                            + " bytes (256 bits) for HS256; got " + keyBytes.length + " bytes");
        }
        this.signingKey = keyBytes;
    }

    /**
     * Mint a step-up token bound to {@code action} + {@code paramsHash}, with its challenge-id
     * segment set to {@code challengeId} (the verified OTP challenge).
     *
     * @return the wire-form token and its expiry.
     */
    public Issued issue(String challengeId, String action, String paramsHash) {
        long expiresAtEpoch = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String payload = challengeId + SEPARATOR + action + SEPARATOR + paramsHash
                + SEPARATOR + expiresAtEpoch;
        String token = payload + SEPARATOR + sign(payload);
        return new Issued(token, Instant.ofEpochSecond(expiresAtEpoch));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign step-up token", e);
        }
    }

    /** A minted token and its expiry instant. */
    public record Issued(String token, Instant expiresAt) {}
}
