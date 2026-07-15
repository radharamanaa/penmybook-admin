package dev.bookforge.adminapi.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Canonical hasher that turns an admin action + identity envelope + params map into the same
 * {@code paramsHash} the entitlements step-up flow binds into the token. Both sides MUST agree
 * on this hash byte-for-byte; otherwise the binding check at admin-api would always reject.
 *
 * <p>Canonical form (deterministic, key-sorted):
 * <pre>{@code
 *   action | staffUserId | targetUserId | k1=v1 & k2=v2 & ...
 * }</pre>
 *
 * <p>Keys are sorted ascending so identical maps in different insertion orders hash the same.
 * Null target ids serialize as the empty string; null params yield an empty body. The hash is
 * SHA-256 of the UTF-8 bytes, base64url-encoded without padding (matches the rest of the mesh).
 *
 * <p>This is NOT a secret — it is a non-keyed canonical hash. The token's signature is what
 * proves the {@code paramsHash} was minted by entitlements; the hash itself only enforces
 * that the params presented at admin-api are the SAME ones the user authorized.
 */
@Component
public class AdminParamsHasher {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String SEPARATOR = "|";
    private static final String PARAM_SEPARATOR = "&";
    private static final String KV_SEPARATOR = "=";

    /**
     * @param action       the admin action (e.g. {@code grant_credit}); MUST match the token's action.
     * @param staffUserId  the acting staff member; binds the token to the human who minted it.
     * @param targetUserId the target user (may be null for refund-only flows).
     * @param params       the action params (may be null/empty); keys sorted before hashing.
     */
    public String compute(String action, UUID staffUserId, UUID targetUserId, Map<String, ?> params) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(action == null ? "" : action);
        canonical.append(SEPARATOR);
        canonical.append(staffUserId == null ? "" : staffUserId);
        canonical.append(SEPARATOR);
        canonical.append(targetUserId == null ? "" : targetUserId);
        canonical.append(SEPARATOR);
        canonical.append(canonicalParams(params));
        return sha256Base64Url(canonical.toString());
    }

    private static String canonicalParams(Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        // TreeMap on the input map's keys gives ascending lexical order; using String.valueOf
        // is enough for the simple types admin params currently carry (int, String, UUID, bool).
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            sorted.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                out.append(PARAM_SEPARATOR);
            }
            out.append(entry.getKey()).append(KV_SEPARATOR).append(entry.getValue());
            first = false;
        }
        return out.toString();
    }

    private static String sha256Base64Url(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
