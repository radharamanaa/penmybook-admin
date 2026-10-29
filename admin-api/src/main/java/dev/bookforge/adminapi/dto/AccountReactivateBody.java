package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /v1/admin/users/{userId}/unsuspend}. The note lands in the audit row.
 * {@code acknowledgeWalletUnknown}: staff confirmed reactivating although billing did not answer, so
 * nobody could tell whether the wallet is frozen by a payment hold. {@code resetContentStrikes}: also
 * reset the curator's content-strike count once the reactivation succeeds. Neither flag is in the
 * step-up hash (the hash binds the target user only); both are recorded in the audit rows.
 */
public record AccountReactivateBody(
        @NotBlank(message = "note is required")
        @Size(min = 8, max = 500, message = "note must be 8 to 500 characters")
        String note,
        Boolean acknowledgeWalletUnknown,
        Boolean resetContentStrikes
) {}
