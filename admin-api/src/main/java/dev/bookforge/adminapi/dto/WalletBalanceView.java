package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /v1/admin/users/{userId}/wallet/top-up}. Carries the new
 * wallet balance returned by the billing service after the credit posted.
 */
public record WalletBalanceView(
        UUID userId,
        long balanceCents
) {}
