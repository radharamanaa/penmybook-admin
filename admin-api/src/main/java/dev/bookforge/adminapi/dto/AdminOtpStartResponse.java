package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /v1/admin/otp/start}. Carries the {@code challengeId} the SPA
 * echoes back to {@code /v1/admin/otp/verify} along with the code the staff admin received, plus
 * the challenge expiry and a human delivery hint. The plaintext code is NOT returned over the
 * wire — for local testing it is read from {@code entitlements.otp_challenges.dev_plaintext_code}.
 */
public record AdminOtpStartResponse(
        UUID challengeId,
        Instant expiresAt,
        String deliveryHint
) {}
