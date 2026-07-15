package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of the FIFO waitlist review queue (admin control plane D11). */
public record WaitlistEntryView(
        UUID userId,
        String email,
        Instant createdAt
) {}
