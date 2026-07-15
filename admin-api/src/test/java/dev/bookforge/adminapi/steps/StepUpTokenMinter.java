package dev.bookforge.adminapi.steps;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Test-scope helper that mints a properly-signed step-up token in the SAME HMAC-SHA256 scheme the
 * entitlements step-up OTP flow uses (spec 02 §7.6) and that {@code StepUpVerifier} checks. Lets the
 * BDD suite supply a genuinely-valid "a valid step-up token" instead of a forgeable string literal,
 * so the security fix stays exercised end-to-end rather than weakened to keep tests green.
 *
 * <p>Wire form: {@code challengeId | action | paramsHash | expiresAtEpoch | base64url(HMAC(payload))}.
 *
 * <p>The two-arg {@link #validFor(String, String)} variant is used when the test needs the token
 * to also pass the params-hash binding check at admin-api (real happy paths). The one-arg
 * {@link #validFor(String)} variant uses a placeholder paramsHash; it is reserved for cases where
 * verification is expected to fail BEFORE the params-hash check (signature tampering, expiry,
 * action rewrite, malformed tokens) so the placeholder is never actually compared.
 */
public final class StepUpTokenMinter {

    /** Matches application.yml's dev default for {@code service-jwt.signing-key}. */
    public static final String SIGNING_KEY = "dev-only-hs256-shared-secret-change-me";

    private static final long TTL_SECONDS = 300L;
    private static final String PLACEHOLDER_PARAMS_HASH = "params-hash";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private StepUpTokenMinter() {
    }

    /**
     * A valid, unexpired token bound to {@code action} with a placeholder paramsHash. Use this
     * only for scenarios where verification must fail BEFORE the params-hash check fires (e.g.
     * tampered signature, action rewrite, expiry, malformed). Happy-path scenarios MUST use
     * {@link #validFor(String, String)}.
     */
    public static String validFor(String action) {
        return validFor(action, PLACEHOLDER_PARAMS_HASH);
    }

    /** A valid, unexpired token bound to {@code action} AND the canonical {@code paramsHash}. */
    public static String validFor(String action, String paramsHash) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        return mint(SIGNING_KEY, UUID.randomUUID().toString(), action, paramsHash, expiresAt);
    }

    /** A token whose signature was minted with a DIFFERENT key — verifier must reject it. */
    public static String mintedWithWrongKey(String action) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        return mint("wrong-key-not-the-real-one-but-32+chars-long",
                UUID.randomUUID().toString(), action, PLACEHOLDER_PARAMS_HASH, expiresAt);
    }

    /** A valid signature whose payload's expiry is well in the past (token is expired). */
    public static String expiredFor(String action) {
        long expiresAt = Instant.now().getEpochSecond() - 60L;
        return mint(SIGNING_KEY, UUID.randomUUID().toString(), action, PLACEHOLDER_PARAMS_HASH, expiresAt);
    }

    /** A correctly-signed token then mutated by flipping a single byte in the signature segment. */
    public static String tamperedSignatureFor(String action) {
        String good = validFor(action);
        int lastSep = good.lastIndexOf('|');
        String payload = good.substring(0, lastSep);
        String sig = good.substring(lastSep + 1);
        char first = sig.charAt(0);
        // Flip to a different valid base64url char to ensure the signature no longer matches.
        char flipped = (first == 'A') ? 'B' : 'A';
        String tamperedSig = flipped + sig.substring(1);
        return payload + "|" + tamperedSig;
    }

    /**
     * A token whose payload was signed for {@code originalAction} and then the action segment was
     * REWRITTEN to {@code presentedAction} WITHOUT re-signing. The signature still covers the
     * original payload, so it won't match the recomputed one.
     */
    public static String actionRewrittenFrom(String originalAction, String presentedAction) {
        String good = validFor(originalAction);
        String[] parts = good.split("\\|", -1);
        parts[1] = presentedAction;
        return String.join("|", parts);
    }

    /** A malformed token with the wrong number of segments. */
    public static String malformed() {
        return "only.two.parts-not-five";
    }

    /** A signed-but-empty-action token — action segment is "", everything else valid + signed. */
    public static String emptyActionSegment() {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        return mint(SIGNING_KEY, UUID.randomUUID().toString(), "", PLACEHOLDER_PARAMS_HASH, expiresAt);
    }

    private static String mint(String key, String challengeId, String action, String paramsHash, long expiresAtEpoch) {
        String payload = challengeId + "|" + action + "|" + paramsHash + "|" + expiresAtEpoch;
        return payload + "|" + sign(key, payload);
    }

    private static String sign(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("test could not sign step-up token", e);
        }
    }
}
