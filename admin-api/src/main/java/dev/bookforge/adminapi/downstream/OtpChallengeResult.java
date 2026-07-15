package dev.bookforge.adminapi.downstream;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of starting an OTP challenge at entitlements ({@code POST /internal/otp/start}). Carries
 * the challenge handle the admin echoes back on verify, the challenge expiry, and a delivery hint.
 */
public record OtpChallengeResult(
        UUID challengeId,
        Instant expiresAt,
        String deliveryHint
) {}
