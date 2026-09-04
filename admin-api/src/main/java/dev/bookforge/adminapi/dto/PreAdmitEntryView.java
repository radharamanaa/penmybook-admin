package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/** One pending (unconsumed, unexpired) pre-admit as listed for the admin SPA. */
public record PreAdmitEntryView(UUID id, String email, UUID admittedBy, Instant createdAt, Instant expiresAt) {
}
