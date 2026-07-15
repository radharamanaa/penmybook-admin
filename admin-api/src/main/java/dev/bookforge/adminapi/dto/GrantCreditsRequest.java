package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.Min;

/**
 * Request body for {@code POST /v1/admin/users/{userId}/grant-credits}.
 *
 * <p>Unified-wallet money model (D7 / P12 / UF-04, locked 2026-06): an admin "grant" now mints
 * spendable MONEY into the user's personal wallet (an {@code ADMIN_CREDIT} wallet credit), NOT a
 * chapter-credit count. The amount is expressed in cents — any positive amount is allowed (the
 * $15 floor applies to self-service top-ups only, not to admin comps).
 *
 * <p>Because this endpoint now mints money it is <strong>step-up (OTP) gated</strong>: the SPA
 * must present the single-use, action-bound step-up token in the {@code X-Step-Up-Token} header,
 * and that token's {@code paramsHash} is bound over THIS {@code amountCents} (and the target
 * user) so the authorization cannot be replayed for a different amount.
 *
 * <p>IMPORTANT — N2 / D11 (master plan §3): an admin grant does NOT change {@code has_ever_paid}.
 * Admin generosity must not silently disable the weekly nurture email; {@code has_ever_paid}
 * flips only when billing records a WALLET-source consume originating from a Stripe Checkout
 * success. The downstream entitlements {@code POST /internal/admin/grant_credit} (wallet
 * {@code ADMIN_CREDIT} credit) never touches the flag.
 *
 * @param amountCents amount of wallet money to grant, in cents; must be a positive number.
 */
public record GrantCreditsRequest(
        @Min(value = 1, message = "amountCents must be a positive number of cents")
        long amountCents
) {}
