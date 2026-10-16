package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Receipt for a staff restore of a {@code PENDING_CARD_REFUND} after Stripe reversed the refund.
 * {@code restoredCents} is 0 when the wallet credit was a no-op (payment stays pending).
 */
public record ForceResolveReversedView(UUID paymentId, long restoredCents) {}
