package dev.bookforge.adminapi.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One signing key per calling service for mesh ({@code /internal/*}) tokens (H1, see
 * docs/security/mesh-keys.md).
 *
 * <p>Each service signs with its OWN secret ({@code MESH_KEY_<CALLER>}) and names itself in
 * {@code sub} as {@code service:<caller>}. A receiver reads that name and checks the signature with
 * that caller's key, so one service's secret can no longer mint tokens as another.
 *
 * <p>Rollout without a switch: while a caller's key is unset or still the SSM placeholder
 * {@code REPLACE_ME}, it signs with the shared {@code SERVICE_JWT_SIGNING_KEY} and receivers accept
 * the shared key for it. Once every caller a receiver knows has a real key, the receiver refuses the
 * shared key for every token, including one that names no caller or an unknown one.
 *
 * <p>The same class (package aside) lives in entitlements, billing, org and admin-api, and the rule
 * matches {@code app/services/mesh_keys.py} in the Python services.
 */
public final class MeshKeys {

    /** Terraform seeds every SSM secret with this until the real value is written. */
    public static final String PLACEHOLDER = "REPLACE_ME";
    public static final String SUBJECT_PREFIX = "service:";

    private final String self;
    private final String sharedKey;
    private final String ownKey;
    /** Every known caller → its configured key ("" while it has none). */
    private final Map<String, String> callerKeys;

    private MeshKeys(String self, String sharedKey, String ownKey, Map<String, String> callerKeys) {
        this.self = self;
        this.sharedKey = sharedKey == null ? "" : sharedKey;
        this.ownKey = configured(ownKey);
        this.callerKeys = callerKeys;
    }

    /**
     * @param self       this service's caller name (the {@code <caller>} in {@code service:<caller>})
     * @param sharedKey  the old shared {@code SERVICE_JWT_SIGNING_KEY}
     * @param ownKey     this service's own {@code MESH_KEY_<SELF>} (blank or placeholder = none yet)
     * @param callers    the services allowed to call this one (its row in mesh-keys.md)
     * @param keys       caller name → configured key; a missing entry counts as "no key yet"
     */
    public static MeshKeys of(String self, String sharedKey, String ownKey,
                              List<String> callers, Map<String, String> keys) {
        Map<String, String> callerKeys = new LinkedHashMap<>();
        if (callers != null) {
            for (String caller : callers) {
                callerKeys.put(caller, configured(keys == null ? null : keys.get(caller)));
            }
        }
        return new MeshKeys(self, sharedKey, ownKey, Map.copyOf(callerKeys));
    }

    /** The key as configured, or "" when it is unset, blank or still the placeholder. */
    public static String configured(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        return PLACEHOLDER.equals(value) ? "" : value;
    }

    /** The caller name in a {@code service:<caller>} subject, or null for anything else. */
    public static String callerOf(String subject) {
        if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
            return null;
        }
        String name = subject.substring(SUBJECT_PREFIX.length());
        return name.isEmpty() ? null : name;
    }

    /** The {@code sub} this service puts in the tokens it signs. */
    public String selfSubject() {
        return SUBJECT_PREFIX + self;
    }

    /** The key this service signs with: its own key once set, else the shared key. */
    public byte[] signingKey() {
        return (ownKey.isEmpty() ? sharedKey : ownKey).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The key to check a token claiming {@code subject} with, or empty when the token must be
     * refused (every known caller has its own key, and this token does not name one of them).
     */
    public Optional<byte[]> verificationKey(String subject) {
        String caller = callerOf(subject);
        if (caller != null) {
            String key = callerKeys.get(caller);
            if (key != null && !key.isEmpty()) {
                return Optional.of(key.getBytes(StandardCharsets.UTF_8));
            }
        }
        boolean everyCallerKeyed = !callerKeys.isEmpty()
                && callerKeys.values().stream().noneMatch(String::isEmpty);
        if (everyCallerKeyed) {
            return Optional.empty();
        }
        return Optional.of(sharedKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Every configured per-caller key (own key first), for the startup dev-key check. */
    public Map<String, String> configuredKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        if (!ownKey.isEmpty()) {
            keys.put(self, ownKey);
        }
        callerKeys.forEach((caller, key) -> {
            if (!key.isEmpty()) {
                keys.put(caller, key);
            }
        });
        return keys;
    }
}
