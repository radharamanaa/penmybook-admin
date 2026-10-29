package dev.bookforge.adminapi.dto;

import java.util.UUID;

/** Receipt for a staff reactivation of a suspended account. */
public record AccountReactivatedView(UUID userId, String accountStatus, UUID auditEntryId) {}
