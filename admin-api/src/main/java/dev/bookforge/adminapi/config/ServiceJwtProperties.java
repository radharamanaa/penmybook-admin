package dev.bookforge.adminapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-account JWT settings for every outbound {@code /internal/*} call, plus the per-caller mesh
 * keys (H1, docs/security/mesh-keys.md): {@code caller} is this service's name in mesh tokens,
 * {@code ownKey} its MESH_KEY_ADMIN_API, {@code callers} the services allowed to call it and
 * {@code callerKeys} their keys. A blank or REPLACE_ME key means "use signingKey" for that service.
 */
@ConfigurationProperties(prefix = "service-jwt")
public record ServiceJwtProperties(
        String signingKey,
        String issuer,
        String audience,
        String caller,
        String ownKey,
        List<String> callers,
        Map<String, String> callerKeys
) {

    private static final String SELF = "admin_api";

    @ConstructorBinding
    public ServiceJwtProperties {
        caller = caller == null || caller.isBlank() ? SELF : caller;
        callers = callers == null ? List.of() : List.copyOf(callers);
        callerKeys = callerKeys == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(callerKeys));
    }

    /** Shared-key-only settings (no per-caller keys): what tests and older wiring construct. */
    public ServiceJwtProperties(String signingKey, String issuer, String audience) {
        this(signingKey, issuer, audience, SELF, "", List.of(), Map.of());
    }

    /** The per-caller key rules built from these settings. */
    public MeshKeys meshKeys() {
        return MeshKeys.of(caller, signingKey, ownKey, callers, callerKeys);
    }
}
