package dev.bookforge.adminapi.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3/S4: outside development, refuse to start with a missing signing key or one of the well-known
 * development values. Local docker compose runs the Java services with
 * {@code SPRING_PROFILES_ACTIVE=dev}; production sets no profile, so a forgotten secret stops the
 * service at boot instead of letting it run on a key every developer knows.
 *
 * <p>Checks the admin-login {@code ADMIN_JWT_SECRET} and the shared {@code SERVICE_JWT_SIGNING_KEY}
 * (each must be set and not a dev value) and every
 * per-caller mesh key that is set (must not be a dev value; unset or REPLACE_ME is the normal
 * rollout state, see {@link MeshKeys}).
 */
@Component
public class SigningKeyStartupGuard {

    public static final String DEV_PROFILE = "dev";

    /** Values that are only ever development or test defaults. */
    public static final Set<String> DEV_KEY_VALUES = Set.of(
            MeshKeys.PLACEHOLDER,
            "dev-only-hs256-shared-secret-change-me",
            "integration-suite-hs256-shared-key-32+bytes",
            "dev-insecure-admin-secret-change-me-32b!");

    public SigningKeyStartupGuard(Environment environment, ServiceJwtProperties serviceJwt,
                                  AdminJwtProperties adminJwt) {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("SERVICE_JWT_SIGNING_KEY", serviceJwt.signingKey());
        required.put("ADMIN_JWT_SECRET", adminJwt.secret());
        Map<String, String> perCaller = new LinkedHashMap<>();
        serviceJwt.meshKeys().configuredKeys()
                .forEach((caller, key) -> perCaller.put("MESH_KEY_" + caller.toUpperCase(), key));
        refuseUnlessDev(environment.acceptsProfiles(Profiles.of(DEV_PROFILE)), required, perCaller);
    }

    /** Throws when not in development and any key is missing (required only) or a dev value. */
    public static void refuseUnlessDev(boolean devProfile, Map<String, String> required,
                                       Map<String, String> perCaller) {
        if (devProfile) {
            return;
        }
        List<String> problems = new ArrayList<>();
        required.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                problems.add(name + " is not set");
            } else if (DEV_KEY_VALUES.contains(value.strip())) {
                problems.add(name + " holds a known development value");
            }
        });
        perCaller.forEach((name, value) -> {
            if (value != null && DEV_KEY_VALUES.contains(value.strip())) {
                problems.add(name + " holds a known development value");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems)
                    + ". Set the real secret, or run with SPRING_PROFILES_ACTIVE=" + DEV_PROFILE
                    + " for local development only.");
        }
    }
}
