package dev.bookforge.adminapi.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /v1/admin/otp/verify}. On {@code verified=true} the
 * {@code stepUpToken} is the single-use, action-bound, params-bound token the SPA sends as the
 * {@code X-Step-Up-Token} header on the gated write; {@code expiresAt} is its expiry. On
 * {@code verified=false} the token is null and {@code failureReason} carries why (e.g.
 * {@code wrong_code}, {@code expired}, {@code locked}).
 */
public record AdminOtpVerifyResponse(
        boolean verified,
        String stepUpToken,
        Instant expiresAt,
        String failureReason
) {}
