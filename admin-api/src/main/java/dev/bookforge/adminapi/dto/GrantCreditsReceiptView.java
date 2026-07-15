package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /v1/admin/users/{userId}/grant-credits}.
 *
 * <p>Unified-wallet money model (D7 / P12): the grant mints an {@code ADMIN_CREDIT} wallet credit
 * via entitlements {@code POST /internal/admin/grant_credit}. That endpoint's response
 * ({@code AdminResult}: {@code success}, {@code auditEntryId}, {@code accountStatus}) does NOT
 * echo the resulting wallet balance, so this receipt carries the amount that was credited (in
 * cents) plus the entitlements audit-row id — the proof the credit + its same-transaction audit
 * row committed.
 *
 * @param userId           the target user whose wallet was credited.
 * @param amountCents       the wallet money credited, in cents (echoes the validated request).
 * @param auditEntryId      the entitlements admin-audit row id (non-null when the credit + audit
 *                          committed in one transaction); null if the downstream rolled back.
 */
public record GrantCreditsReceiptView(
        UUID userId,
        long amountCents,
        UUID auditEntryId
) {}
