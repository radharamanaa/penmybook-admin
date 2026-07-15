package dev.bookforge.adminapi.service;

import dev.bookforge.adminapi.config.ServiceJwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Verifies the action-bound step-up token that must accompany every admin write.
 *
 * <p>The token is the single-use, action-bound, params-bound HMAC token minted by the entitlements
 * step-up OTP flow (spec 02 §7.6). It is the SAME HMAC-SHA256 scheme used across the mesh, signed
 * with the shared {@code SERVICE_JWT_SIGNING_KEY}. The wire form is a pipe-delimited payload plus a
 * detached signature:
 *
 * <pre>{@code challengeId | action | paramsHash | expiresAtEpoch | base64url(HMAC-SHA256(payload))}</pre>
 *
 * <p>This verifier proves the token is authentic and timely: <strong>signature</strong> matches the
 * recomputed HMAC under the shared key (constant-time compare), the embedded <strong>action</strong>
 * is the one being performed, and the token is <strong>not expired</strong>. It also exposes the
 * decoded {@code challengeId}, {@code paramsHash}, and {@code expiresAt} on success so the
 * orchestrating service can enforce the remaining two policies that depend on per-action state:
 * single-use (via {@link dev.bookforge.adminapi.security.StepUpTokenStore}) and params-binding
 * (via {@link dev.bookforge.adminapi.security.AdminParamsHasher}).
 *
 * <p>String-matching the action name (the old {@code "valid-token"} / {@code "token-for-<action>"}
 * stub) is explicitly forbidden by the contract — a forged value with no valid signature must be
 * rejected as {@code step_up_invalid}.
 */
@Component
public class StepUpVerifier {

    private static final int EXPECTED_PARTS = 5;
    private static final int CHALLENGE_ID_INDEX = 0;
    private static final int ACTION_INDEX = 1;
    private static final int PARAMS_HASH_INDEX = 2;
    private static final int EXPIRES_INDEX = 3;
    private static final int SIGNATURE_INDEX = 4;
    private static final String SEPARATOR = "|";
    private static final int HS256_MIN_KEY_BYTES = 32; // 256 bits — JWA minimum for HS256
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] signingKey;

    public StepUpVerifier(ServiceJwtProperties properties) {
        byte[] keyBytes = properties.signingKey().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "SERVICE_JWT_SIGNING_KEY must be at least "
                            + HS256_MIN_KEY_BYTES
                            + " bytes (256 bits) for HS256; got "
                            + keyBytes.length
                            + " bytes");
        }
        this.signingKey = keyBytes;
    }

    /**
     * Outcome of {@link #verify(String, String)}. {@link Valid} carries the decoded payload so
     * the caller can drive the policies that depend on per-action state (single-use, params bind).
     */
    public sealed interface Result permits Result.Valid, Result.Missing, Result.Invalid {
        record Valid(String challengeId, String paramsHash, long expiresAtEpoch) implements Result {}
        record Missing() implements Result {}
        record Invalid() implements Result {}
    }

    /**
     * @param token  the value of the {@code X-Step-Up-Token} header (may be null/blank).
     * @param action the action the token must be bound to (e.g. "grant_credit", "refund").
     */
    public Result verify(String token, String action) {
        if (token == null || token.isBlank()) {
            return new Result.Missing();
        }
        String[] parts = token.split("\\|", -1);
        if (parts.length != EXPECTED_PARTS) {
            return new Result.Invalid();
        }
        String payload = parts[CHALLENGE_ID_INDEX] + SEPARATOR + parts[ACTION_INDEX]
                + SEPARATOR + parts[PARAMS_HASH_INDEX] + SEPARATOR + parts[EXPIRES_INDEX];
        if (!signaturesMatch(sign(payload), parts[SIGNATURE_INDEX])) {
            return new Result.Invalid();
        }
        if (!action.equals(parts[ACTION_INDEX])) {
            return new Result.Invalid();
        }
        long expiresAtEpoch;
        try {
            expiresAtEpoch = Long.parseLong(parts[EXPIRES_INDEX]);
        } catch (NumberFormatException e) {
            return new Result.Invalid();
        }
        if (Instant.now().getEpochSecond() >= expiresAtEpoch) {
            return new Result.Invalid();
        }
        return new Result.Valid(parts[CHALLENGE_ID_INDEX], parts[PARAMS_HASH_INDEX], expiresAtEpoch);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot verify step-up token signature", e);
        }
    }

    private boolean signaturesMatch(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
