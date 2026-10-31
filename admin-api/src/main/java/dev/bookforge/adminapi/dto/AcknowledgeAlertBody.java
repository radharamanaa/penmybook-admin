package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /v1/admin/alerts/{alertId}/acknowledge}.
 *
 * <p>{@code note} is required: closing an alert grants nothing, so the note is the whole record of
 * what staff decided. Billing rejects a blank note too.
 */
public record AcknowledgeAlertBody(@NotBlank(message = "note is required") String note) {}
