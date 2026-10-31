package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One money alert as billing records it ({@code billing.admin_alerts}).
 *
 * <p>{@code emailError} is carried so the screen can say when the alert's email was never sent:
 * an alert nobody was emailed about must not look like an ordinary row. The three
 * {@code acknowledged*} / {@code resolutionNote} fields are null while the alert is open.
 */
public record MoneyAlertEntry(
        UUID id,
        String alertType,
        String severity,
        String subject,
        String body,
        String ownerType,
        UUID ownerId,
        UUID paymentId,
        Instant createdAt,
        String emailError,
        Instant acknowledgedAt,
        UUID acknowledgedBy,
        String resolutionNote
) {}
