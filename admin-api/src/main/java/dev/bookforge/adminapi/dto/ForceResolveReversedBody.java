package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /v1/admin/users/{userId}/payments/{paymentId}/force-resolve-reversed}.
 *
 * <p>{@code note} is bound into the {@code refund} step-up {@code paramsHash} together with
 * {@code forceResolve=true} so a payout token cannot be replayed onto a restore. Admin-api
 * and billing both check that hash. Billing does not persist the note (LOW); it is still
 * required so staff type the Stripe refund id they confirmed.
 */
public record ForceResolveReversedBody(
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {}
