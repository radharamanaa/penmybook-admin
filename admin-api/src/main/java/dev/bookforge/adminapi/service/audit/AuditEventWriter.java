package dev.bookforge.adminapi.service.audit;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Cross-service audit-log writer. admin-api owns no database of its own; per master plan §4.9
 * + N7 (locked 2026-05-31) the {@code admin_api.audit_log} table physically lives inside the
 * entitlements Postgres schema, so every audit row is shipped over HTTP via
 * {@code POST /internal/audit-log} on entitlements.
 *
 * <p>Writes are <strong>synchronous</strong> and <strong>fail-closed for the audit trail</strong>:
 * a successful admin action (or a 403 reject) returns to the caller, but if the audit-log POST
 * to entitlements fails we log a WARN with the full payload that was about to be persisted. We
 * never swallow the original 403 response and we never fail-open by surfacing the audit failure
 * as the action's own failure — the action committed (or the access was correctly denied) and
 * that fact must reach the caller; the forensic record is a best-effort secondary write that
 * the operator must reconcile via the WARN log.
 *
 * <p>The 5-second budget for the audit POST is enforced by the {@link DownstreamClient}
 * implementation (HttpClient timeout); this writer is just the policy layer.
 */
@Component
public class AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventWriter.class);
    private static final String DECISION_ALLOW = "ALLOW";
    private static final String DECISION_DENY = "DENY";

    private final DownstreamClient downstream;

    public AuditEventWriter(DownstreamClient downstream) {
        this.downstream = downstream;
    }

    /** Write an ALLOW-decision audit row (admin action succeeded). */
    public void writeAllow(UUID actorUserId, String action, String resourceType, String resourceId,
                           Map<String, Object> oldValue, Map<String, Object> newValue, String ip) {
        write(actorUserId, action, resourceType, resourceId, oldValue, newValue,
                DECISION_ALLOW, ip);
    }

    /**
     * Write a DENY-decision audit row (admin action rejected — captures the forensic attempt).
     * Called from {@code SecurityConfig.AccessDeniedHandler} on every 403; MUST NOT fail-open.
     */
    public void writeDeny(UUID actorUserId, String action, String resourceType, String resourceId,
                          Map<String, Object> attemptedValue, String ip) {
        write(actorUserId, action, resourceType, resourceId, null, attemptedValue,
                DECISION_DENY, ip);
    }

    private void write(UUID actorUserId, String action, String resourceType, String resourceId,
                       Map<String, Object> oldValue, Map<String, Object> newValue,
                       String decision, String ip) {
        try {
            downstream.writeAuditLog(new AuditLogEntry(
                    actorUserId, action, resourceType, resourceId, oldValue, newValue,
                    decision, ip));
        } catch (RuntimeException e) {
            // Best-effort: log everything we tried to persist so the operator can replay manually.
            log.warn("audit-log write failed decision={} actor={} action={} resource={}/{} ip={} reason={}",
                    decision, actorUserId, action, resourceType, resourceId, ip, e.getMessage());
        }
    }
}
