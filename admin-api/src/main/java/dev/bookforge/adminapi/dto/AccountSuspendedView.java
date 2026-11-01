package dev.bookforge.adminapi.dto;

import java.util.UUID;

/** Receipt for a staff suspension. */
public record AccountSuspendedView(UUID userId, String accountStatus, UUID auditEntryId) {}
