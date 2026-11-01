package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /v1/admin/users/{userId}/suspend}. The note lands in the audit row. */
public record AccountSuspendBody(
        @NotBlank(message = "note is required")
        @Size(min = 8, max = 500, message = "note must be 8 to 500 characters")
        String note
) {}
