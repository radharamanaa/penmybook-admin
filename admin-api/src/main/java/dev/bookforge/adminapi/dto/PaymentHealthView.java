package dev.bookforge.adminapi.dto;

import java.time.Instant;

/**
 * Parsed shape of billing {@code GET /internal/admin/payments/health} — payment-integration
 * health tiles for the admin Spend dashboard. Relayed verbatim (counts via requireLong;
 * {@code lastCompletedWebhookAt} is nullable Instant).
 */
public record PaymentHealthView(
        long stuckRefundCount,
        long stuckTopupCount,
        long abandonedTopupCount,
        Instant lastCompletedWebhookAt,
        long completedWebhooks24h,
        long stuckRefundThresholdMinutes,
        long stuckTopupThresholdMinutes
) {}
