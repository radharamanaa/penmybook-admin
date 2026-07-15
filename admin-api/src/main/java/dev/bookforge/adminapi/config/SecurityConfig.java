package dev.bookforge.adminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.service.audit.AuditEventWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Security policy for admin-api.
 *
 * <p>Authentication (decision log D4/D16, docs/admin-control-plane/): every
 * {@code /v1/admin/**} call MUST carry the HS256 admin-login JWT minted by the colocated
 * {@code book_admin} service — signed with {@code ADMIN_JWT_SECRET} (deliberately distinct
 * from the mesh {@code SERVICE_JWT_SIGNING_KEY}, so a service token can never open this
 * surface and a stolen admin token can never call the mesh). The decoder additionally
 * enforces {@code iss=book_admin} and {@code aud=bookforge-admin}.
 *
 * <p>Authorisation: a custom {@link JwtAuthenticationConverter} maps the admin-token claim
 * {@code role=admin} to the Spring authority {@code ROLE_PLATFORM_ADMIN} (the authority name
 * is retained so every existing {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")} guard is
 * untouched). The token's {@code sub} is the admin's stable UUID (D16) — it parses cleanly
 * as the audit actor. Non-admins are rejected with HTTP 403 by Spring Security; the bean
 * below funnels that rejection through {@link AuditingAccessDeniedHandler}, which writes a
 * {@code decision=DENY} row to {@code admin_api.audit_log} via {@link AuditEventWriter} for
 * forensic review (T25 / R26).
 *
 * <p>The audit write is <strong>synchronous</strong>: if the entitlements call fails, we log a
 * WARN with the full attempted-action payload and still return 403 to the caller — we do NOT
 * fail-open by allowing the request through, and we do NOT translate the audit failure into a
 * 500 that masks the 403.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Roles required by {@code @PreAuthorize} are matched by literal name; the {@code ROLE_}
     * prefix is supplied by Spring's {@link SimpleGrantedAuthority} convention.
     */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    /** JWT claim carrying the admin role on the book_admin-minted token (D16). */
    public static final String CLAIM_ROLE = "role";
    /** The role value that grants the platform-admin authority. */
    public static final String ROLE_CLAIM_ADMIN_VALUE = "admin";
    private static final String AUTHORITY_PLATFORM_ADMIN = "ROLE_" + ROLE_PLATFORM_ADMIN;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AccessDeniedHandler accessDeniedHandler,
                                                   NimbusJwtDecoder jwtDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v1/admin/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handler -> handler.accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * HS256 decoder pinned to the admin-login secret (D4: distinct from the mesh service key).
     * ADR 0002: HS256 only — any other {@code alg} is rejected during signature verification by
     * NimbusJwtDecoder. Beyond the signature, the decoder validates {@code exp} (+ skew),
     * {@code iss=book_admin}, and {@code aud=bookforge-admin}, so a mesh service token (different
     * key, different iss/aud) fails on every layer — and a replayed admin token cannot be
     * presented to any mesh service that validates the shared key.
     */
    @Bean
    public NimbusJwtDecoder jwtDecoder(AdminJwtProperties properties) {
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(keySpec).macAlgorithm(
                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator));
        return decoder;
    }

    /**
     * Converts an inbound admin JWT into an authentication that carries the
     * {@code ROLE_PLATFORM_ADMIN} authority IFF the {@code role} claim equals {@code admin}.
     * Any other shape (missing / different value / non-string) yields a regular authenticated
     * principal with no extra authority.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AdminRoleAuthoritiesConverter());
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    public AccessDeniedHandler auditingAccessDeniedHandler(AuditEventWriter auditEventWriter,
                                                           ObjectMapper objectMapper) {
        return new AuditingAccessDeniedHandler(auditEventWriter, objectMapper);
    }

    /** Maps {@code role=admin} on the book_admin token to {@code ROLE_PLATFORM_ADMIN}. */
    static final class AdminRoleAuthoritiesConverter
            implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            Object claim = jwt.getClaim(CLAIM_ROLE);
            if (ROLE_CLAIM_ADMIN_VALUE.equals(claim)) {
                authorities.add(new SimpleGrantedAuthority(AUTHORITY_PLATFORM_ADMIN));
            }
            return authorities;
        }
    }

    /**
     * Writes a {@code decision=DENY} row to {@code admin_api.audit_log} on every 403 so the
     * operator has a forensic trail of self-discount / unauthorised-refund attempts (T25 / R26).
     * Failures of the audit write are logged WARN but never leak into the 403 response shape.
     */
    static final class AuditingAccessDeniedHandler implements AccessDeniedHandler {

        private static final Logger log = LoggerFactory.getLogger(AuditingAccessDeniedHandler.class);
        private static final UUID UNKNOWN_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

        private final AuditEventWriter auditEventWriter;
        private final ObjectMapper objectMapper;

        AuditingAccessDeniedHandler(AuditEventWriter auditEventWriter, ObjectMapper objectMapper) {
            this.auditEventWriter = auditEventWriter;
            this.objectMapper = objectMapper;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            UUID actorUserId = resolveActor();
            String ip = clientIp(request);
            Map<String, Object> attempted = new HashMap<>();
            attempted.put("method", request.getMethod());
            attempted.put("path", request.getRequestURI());
            attempted.put("reason", accessDeniedException.getMessage());
            try {
                auditEventWriter.writeDeny(actorUserId,
                        request.getMethod() + " " + request.getRequestURI(),
                        "HTTP", request.getRequestURI(), attempted, ip);
            } catch (RuntimeException e) {
                // Defense-in-depth — the writer already swallows; this catch is so a programming
                // error in a future refactor cannot fail-open by turning the 403 into a 500.
                log.warn("audit-log DENY write threw despite writer guard path={} reason={}",
                        request.getRequestURI(), e.getMessage());
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("error", "access_denied", "code", "ACCESS_DENIED")));
        }

        private UUID resolveActor() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                return UNKNOWN_ACTOR;
            }
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                String sub = jwtAuth.getToken().getSubject();
                if (sub != null) {
                    try {
                        return UUID.fromString(sub);
                    } catch (IllegalArgumentException ignored) {
                        // sub is a non-UUID identifier — fall through to UNKNOWN_ACTOR
                    }
                }
            } else if (authentication instanceof AbstractAuthenticationToken token
                    && token.getName() != null) {
                try {
                    return UUID.fromString(token.getName());
                } catch (IllegalArgumentException ignored) {
                    // name is non-UUID — fall through
                }
            }
            return UNKNOWN_ACTOR;
        }

        private String clientIp(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return comma == -1 ? forwarded.trim() : forwarded.substring(0, comma).trim();
            }
            return request.getRemoteAddr();
        }
    }
}
