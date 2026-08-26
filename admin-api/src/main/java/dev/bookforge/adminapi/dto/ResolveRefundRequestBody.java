package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * An admin's decision on a user's refund request.
 *
 * <p>{@code amountCents} is the ADMIN's figure and may be more or less than the user asked for;
 * null means "everything currently refundable". Billing clamps it to the live card-funded balance.
 * Zero is refused — a $0 approval would close the request having paid nothing.
 *
 * <p>{@code note} reaches the user verbatim, so it must be written for them, not for staff.
 * It is required on the write but is <strong>not</strong> bound into the step-up params hash,
 * so staff may edit it after OTP start without minting a new token.
 */
public record ResolveRefundRequestBody(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @Positive Long amountCents,
        @NotBlank @Size(max = 2000) String note
) {}
