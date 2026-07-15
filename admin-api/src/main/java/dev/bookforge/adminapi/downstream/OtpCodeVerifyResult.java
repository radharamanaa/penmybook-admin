package dev.bookforge.adminapi.downstream;

/**
 * Result of verifying an OTP code at entitlements ({@code POST /internal/otp/verify}). admin-api
 * only consumes the {@code verified} verdict and {@code failureReason} — it does NOT use the
 * entitlements step-up token (that is bound over entitlements' own params-hash scheme). On a
 * positive verdict admin-api mints its OWN token via {@link
 * dev.bookforge.adminapi.security.StepUpTokenIssuer} bound over {@link
 * dev.bookforge.adminapi.security.AdminParamsHasher}'s canonical hash.
 */
public record OtpCodeVerifyResult(
        boolean verified,
        String failureReason
) {}
