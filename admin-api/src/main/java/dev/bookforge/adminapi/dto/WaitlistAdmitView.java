package dev.bookforge.adminapi.dto;

/**
 * Outcome of an operator's waitlist approval (admin control plane D11). {@code admitted} is true
 * only when a weekly slot was claimed and the user flipped to ACTIVE; {@code reason} is
 * {@code "weekly_limit_reached"} (raise the limit) or {@code "not_waitlisted"} (no-op) otherwise.
 */
public record WaitlistAdmitView(boolean admitted, String accountStatus, String reason) {}
