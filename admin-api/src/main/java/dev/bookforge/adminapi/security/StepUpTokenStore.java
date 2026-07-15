package dev.bookforge.adminapi.security;

/**
 * Single-use tracker for step-up tokens. The {@code challengeId} segment of a step-up token is its
 * jti; once a token has been successfully verified and used by admin-api, the same challenge id
 * MUST NOT be honored a second time within its TTL — that closes the in-TTL replay window
 * (entitlements also marks the underlying OTP challenge consumed, but enforcing here is
 * defense-in-depth that catches the replay BEFORE any downstream call is issued).
 *
 * <p>The dev/test implementation is in-memory. Production behind multiple admin-api tasks must
 * back this with Redis/DynamoDB so the dedupe is mesh-wide; the interface is intentionally narrow
 * so swapping the implementation is mechanical.
 */
public interface StepUpTokenStore {

    /**
     * Atomically marks {@code challengeId} as consumed. Returns {@code true} on the first
     * successful call (the caller may proceed); {@code false} if {@code challengeId} was already
     * consumed (the caller MUST reject the action as a replay).
     *
     * @param challengeId  the challenge-id segment of the step-up token.
     * @param expiresAtEpochSeconds the token's embedded expiry — used as the entry's eviction
     *                              time so the store does not grow without bound.
     */
    boolean markConsumedOnce(String challengeId, long expiresAtEpochSeconds);
}
