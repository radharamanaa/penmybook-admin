package dev.bookforge.adminapi.service.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Payload posted to entitlements {@code POST /internal/audit-log}. Mirrors the
 * {@code admin_api.audit_log} table columns (master plan §4.9 V00X migration row).
 *
 * <p>{@code oldValue} and {@code newValue} are arbitrary JSON-serialisable maps; null means
 * "no payload" (NOT "empty payload") and serialises to JSON {@code null}.
 *
 * @param actorUserId  the staff member who initiated the action.
 * @param action       enum-style action label (e.g. {@code WALLET_TOPUP}, {@code REFUND},
 *                     {@code GRANT_CREDITS}, {@code SET_PLATFORM_ADMIN}, {@code UPSERT_ORG_PRICING}).
 * @param resourceType e.g. {@code USER}, {@code ORG}, {@code PAYMENT}.
 * @param resourceId   id of the affected resource (typically a UUID, but kept as String to
 *                     keep the contract open for HTTP-path resources captured by the 403 path).
 * @param oldValue     prior state for forensic diff; null on insert or DENY rows.
 * @param newValue     new state for an ALLOW row, or the attempted value for a DENY row.
 * @param decision     {@code ALLOW} or {@code DENY}.
 * @param ip           client IP captured at the controller; null if unknown.
 */
public record AuditLogEntry(
        UUID actorUserId,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> oldValue,
        Map<String, Object> newValue,
        String decision,
        String ip
) {}
