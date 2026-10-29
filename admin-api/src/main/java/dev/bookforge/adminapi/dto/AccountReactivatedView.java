package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Receipt for a staff reactivation of a suspended account. {@code contentStrikesReset} is null when
 * no reset was asked for, true when the curator reset the count, false when the reset failed after
 * the reactivation succeeded ({@code contentStrikesResetError} says why; the reactivation stands).
 */
public record AccountReactivatedView(UUID userId, String accountStatus, UUID auditEntryId,
                                     Boolean contentStrikesReset, String contentStrikesResetError) {}
