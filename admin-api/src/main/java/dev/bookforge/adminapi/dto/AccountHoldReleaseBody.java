package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /v1/admin/users/{userId}/unsuspend} and
 * {@code POST /v1/admin/users/{userId}/wallet/unfreeze}. The note says why the hold is lifted (for
 * example the dispute that was won) and lands in the audit row; it is not part of the step-up hash.
 */
public record AccountHoldReleaseBody(
        @NotBlank(message = "note is required")
        @Size(min = 8, max = 500, message = "note must be 8 to 500 characters")
        String note
) {}
