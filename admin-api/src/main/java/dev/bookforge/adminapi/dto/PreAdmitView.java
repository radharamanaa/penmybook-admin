package dev.bookforge.adminapi.dto;

import java.time.Instant;

/**
 * Outcome of an admin pre-admit. {@code reason} is one of {@code invalid_email},
 * {@code already_registered}, {@code duplicate}, {@code weekly_limit_reached}; null on success.
 * {@code expiresAt} is set only on success (when the pending invite stops bypassing the gate).
 */
public record PreAdmitView(boolean admitted, String reason, Instant expiresAt) {
}
