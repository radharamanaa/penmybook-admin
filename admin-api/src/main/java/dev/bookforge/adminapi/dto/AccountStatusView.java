package dev.bookforge.adminapi.dto;

import java.util.UUID;

/** Minimal view used to assert the effect of a suspend/unsuspend. */
public record AccountStatusView(
        UUID userId,
        String accountStatus    // ACTIVE | SUSPENDED | SOFT_DELETED
) {}
