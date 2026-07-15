package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Common envelope for an admin write. Every write carries the staff identity and a
 * step-up token; the action and its params are bound into the token to prevent replay.
 */
public record AdminCommand(
        UUID staffUserId,
        UUID targetUserId,
        String action,          // e.g. "grant_credit", "suspend", "flag_developer", "refund"
        String stepUpToken,     // single-use, action-bound; null/blank means no step-up presented
        java.util.Map<String, Object> params
) {}
