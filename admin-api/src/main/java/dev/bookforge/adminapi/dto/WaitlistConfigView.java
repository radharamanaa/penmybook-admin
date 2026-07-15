package dev.bookforge.adminapi.dto;

/** Closed-beta waitlist config + this week's approval usage ({@code GET/POST /v1/admin/waitlist/config}). */
public record WaitlistConfigView(boolean enabled, int weeklyApprovalLimit, int admittedThisWeek) {}
