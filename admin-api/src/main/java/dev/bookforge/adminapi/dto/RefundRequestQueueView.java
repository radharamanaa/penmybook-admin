package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Staff refund-request queue envelope. {@code requests} being empty is ambiguous on its own:
 * it can mean the queue is genuinely clear, or that billing did not answer. {@code degraded}
 * is the latter. {@code identityDegraded} is quieter — names failed to load, UUIDs are still
 * trustworthy — and must never reuse the red banner.
 */
public record RefundRequestQueueView(
        boolean degraded,
        boolean identityDegraded,
        String status,
        List<RefundRequestQueueEntry> requests
) {}
