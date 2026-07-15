package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /v1/admin/otp/verify}. Submits the 6-digit OTP code the staff admin
 * received for the {@code challengeId} returned by {@code /v1/admin/otp/start}. On success the
 * response carries an action-bound, params-bound, single-use step-up token the SPA then sends as
 * {@code X-Step-Up-Token} on the gated write.
 *
 * <p>{@code action} / {@code targetUserId} / {@code params} MUST match what {@code start} was
 * called with — they are bound into the minted token's {@code paramsHash} and re-checked by the
 * write endpoint, so the values presented here are the values the staff admin authorised.
 *
 * @param challengeId  the challenge handle from {@code /v1/admin/otp/start}.
 * @param code         the 6-digit OTP code (dev: read from {@code otp_challenges.dev_plaintext_code}).
 * @param action       the admin step-up action ({@code grant_credit} / {@code refund}).
 * @param targetUserId the user the action targets (bound into the token's paramsHash).
 * @param params       the action params (bound into the token's paramsHash); may be empty.
 */
public record AdminOtpVerifyRequest(
        @NotNull(message = "challengeId is required") UUID challengeId,
        @NotBlank(message = "code is required")
        @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code,
        @NotBlank(message = "action is required") String action,
        UUID targetUserId,
        Map<String, Object> params
) {}
