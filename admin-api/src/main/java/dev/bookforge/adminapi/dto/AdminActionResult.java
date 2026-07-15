package dev.bookforge.adminapi.dto;

import java.util.UUID;

public record AdminActionResult(
        boolean success,
        UUID auditEntryId,       // non-null when the action + audit row committed
        String rejectionReason   // e.g. "step_up_required", "step_up_invalid", "forbidden"; null on success
) {}
