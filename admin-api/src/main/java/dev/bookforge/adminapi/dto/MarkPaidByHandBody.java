package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for {@code POST /v1/admin/refund-requests/{requestId}/mark-paid-by-hand}.
 *
 * <p>{@code ownerId} is the request's owner (a user, or an org for an org request) and is the
 * step-up target; billing checks it against the request. {@code providerRefundId} is the Stripe
 * refund id staff made in the Dashboard ({@code re_…}) and is bound into the step-up hash. The
 * {@code note} is not hashed; billing requires it when the Stripe amount is below the approved one.
 */
public record MarkPaidByHandBody(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 255) String providerRefundId,
        @Size(max = 2000) String note
) {}
