package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /v1/admin/otp/start}. Starts an OTP step-up challenge for the
 * signed-in staff admin (the code is mailed to them; in dev/pre-SES it lands in
 * {@code entitlements.otp_challenges.dev_plaintext_code} for local retrieval).
 *
 * <p>The {@code action} is the admin-api step-up action the resulting token will be bound to —
 * {@code grant_credit} or {@code refund}. {@code targetUserId} and {@code params} are bound into
 * the eventual token's {@code paramsHash} (via {@link dev.bookforge.adminapi.security.AdminParamsHasher})
 * so the authorisation cannot be replayed for a different target/amount. For a grant {@code params}
 * carries {@code amountCents}; for a refund {@code paymentId} + {@code refundAmountCents}
 * and, when paying out an approved request, {@code refundRequestId}.
 *
 * @param action       the admin step-up action ({@code grant_credit} / {@code refund}).
 * @param targetUserId the user the action targets (bound into the token's paramsHash).
 * @param params       the action params (bound into the token's paramsHash); may be empty.
 */
public record AdminOtpStartRequest(
        @NotBlank(message = "action is required") String action,
        UUID targetUserId,
        Map<String, Object> params
) {}
