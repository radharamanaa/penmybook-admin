package dev.bookforge.adminapi.downstream;

import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.GlobalWalletSummaryView;
import dev.bookforge.adminapi.dto.MoneyFlowsView;
import dev.bookforge.adminapi.dto.OrgPricingRuleView;
import dev.bookforge.adminapi.dto.OrgView;
import dev.bookforge.adminapi.dto.PlatformAdminUpdateView;
import dev.bookforge.adminapi.dto.SpendReportView;
import dev.bookforge.adminapi.dto.UserSearchResultEntry;
import dev.bookforge.adminapi.dto.WaitlistAdmitView;
import dev.bookforge.adminapi.dto.WaitlistConfigView;
import dev.bookforge.adminapi.dto.WaitlistEntryView;
import dev.bookforge.adminapi.dto.WalletBalanceView;
import dev.bookforge.adminapi.dto.WalletSummaryView;
import dev.bookforge.adminapi.service.audit.AuditLogEntry;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port to the internal service mesh. admin-api is a CALLER only: it delegates each
 * write to entitlements ({@code /internal/admin/*} or {@code /internal/entitlements/*}) or
 * billing ({@code /internal/refund} / {@code /internal/wallet/credit}), and reads org/user
 * state read-through. Implementations carry the service-account JWT and the per-callee base
 * URLs from config; see contracts/internal-api.md.
 */
public interface DownstreamClient {

    // ───────── Step-up-gated writes ─────────

    /**
     * entitlements {@code POST /internal/admin/grant_credit} — grant wallet money ({@code
     * amountCents}, owner-scoped) as an {@code ADMIN_CREDIT} wallet credit. The command's
     * {@code params} carry {@code amountCents} (and an optional {@code reason}); the same
     * {@code amountCents} is bound into the step-up token's {@code paramsHash} (D7 / P12 / UF-04).
     */
    DownstreamResult grantCredit(AdminCommand command);

    /** entitlements {@code POST /internal/admin/suspend}. */
    DownstreamResult suspend(AdminCommand command);

    /** entitlements {@code POST /internal/admin/unsuspend}. */
    DownstreamResult unsuspend(AdminCommand command);

    /** entitlements {@code POST /internal/admin/flag_developer}. */
    DownstreamResult flagDeveloper(AdminCommand command);

    /**
     * billing {@code POST /internal/refund} — the SINGLE refund path (D6 / P11). Step-up gated
     * via {@code AdminService.refund} → {@code runWrite(REFUND)}. The result carries the clamped
     * ask ({@code requestedCents}) and what was actually pulled back ({@code walletDebitedCents})
     * so the controller can return "asked X, refunded Y" (F1 / D). Idempotent on the supplied
     * {@code idempotencyKey} per AD8 / G14.
     */
    DownstreamResult refund(AdminCommand command);

    /** read-through account status for the target user (entitlements admin profile). */
    AccountStatusView accountStatus(UUID userId);

    // ───────── Admin step-up OTP (F2) ─────────

    /**
     * entitlements {@code POST /internal/otp/start} — mint an OTP code for the staff admin. The
     * code is hashed at rest and (dev/pre-SES) also stored plaintext in
     * {@code otp_challenges.dev_plaintext_code} for local retrieval. Returns the challenge handle.
     *
     * @param staffUserId the staff admin the code is for (JWT sub — the book_admin admin UUID).
     * @param staffEmail  delivery address (D17) — staff admins are not entitlements users, so
     *                    entitlements cannot resolve the email itself. Nullable.
     * @param otpAction   the entitlements {@code OtpAction} enum NAME (e.g. {@code ADMIN_WRITE}).
     * @param actionParams the params bound into the OTP challenge (must match on verify).
     */
    OtpChallengeResult otpStart(UUID staffUserId, String staffEmail, String otpAction,
                                java.util.Map<String, Object> actionParams);

    /**
     * entitlements {@code POST /internal/otp/verify} — check a submitted OTP code for a challenge.
     * admin-api uses only the {@code verified} verdict; it mints its OWN step-up token on success.
     *
     * @param challengeId  the challenge handle from {@link #otpStart}.
     * @param code         the 6-digit code the staff admin submitted.
     * @param otpAction    the entitlements {@code OtpAction} enum NAME used at start.
     * @param actionParams the params used at start (must match for the verify to succeed).
     */
    OtpCodeVerifyResult otpVerify(UUID challengeId, String code, String otpAction,
                                  java.util.Map<String, Object> actionParams);

    /** org {@code GET /internal/orgs/{orgId}} — org context for the admin portal user/org view. */
    OrgView org(UUID orgId);

    // ───────── Phase 3b admin-portal endpoints ─────────

    /**
     * billing {@code POST /internal/wallet/credit} — admin wallet top-up. Returns the new
     * wallet balance so the controller can surface it directly to the SPA.
     */
    WalletBalanceView walletTopUp(UUID userId, long amountCents, String reason);

    /**
     * entitlements {@code PATCH /internal/admin/users/{userId}} — set the {@code is_platform_admin}
     * boolean. Returns the post-update value.
     */
    PlatformAdminUpdateView setIsPlatformAdmin(UUID userId, boolean isPlatformAdmin);

    /**
     * entitlements {@code GET /internal/admin/users/search?email=&limit=} — fuzzy user search.
     */
    List<UserSearchResultEntry> searchUsers(String emailQuery, int limit);

    /**
     * entitlements {@code POST /internal/pricing/org-override} — upsert a per-org pricing
     * override. Returns the persisted row + the prior value (null on first insert) so the
     * controller can record both halves in the audit log.
     */
    OrgPricingRuleView upsertOrgPricing(UUID orgId, String action, long valueCents);

    /**
     * entitlements {@code POST /internal/pricing/global-override} — replace the GLOBAL default
     * price for an action (admin control plane D5/D13; $0 is legal — free digital publish).
     * Returned view carries {@code orgId=null}.
     */
    OrgPricingRuleView upsertGlobalPricing(String action, long valueCents);

    /**
     * entitlements {@code GET /internal/pricing/active-by-action} — current GLOBAL price (cents)
     * per priced action, for the portal's price read-back. Keyed by EntitlementAction name.
     */
    java.util.Map<String, Long> currentPricing();

    /**
     * entitlements {@code GET /internal/admin/waitlist} — the FIFO waitlist review queue
     * (admin control plane D11). Each entry: {userId, email, createdAt}.
     */
    List<WaitlistEntryView> waitlistQueue(int limit);

    /**
     * entitlements {@code POST /internal/admin/waitlist-admit} — WAITLISTED → ACTIVE.
     * Idempotent upstream; {@code success=false} means the user was already non-waitlisted.
     */
    WaitlistAdmitView waitlistAdmit(UUID targetUserId, UUID staffUserId);

    /** entitlements {@code GET /internal/admin/waitlist/config} — current config + this-week usage. */
    WaitlistConfigView waitlistConfig();

    /** entitlements {@code POST /internal/admin/waitlist/config} — partial update (null = unchanged). */
    WaitlistConfigView updateWaitlistConfig(Boolean enabled, Integer weeklyApprovalLimit, String updatedBy);

    /**
     * entitlements {@code POST /internal/audit-log} — ship one audit row. Synchronous; callers
     * MUST tolerate this throwing without flipping their own response from success to failure
     * (the audit failure is logged + reconciled out-of-band).
     */
    void writeAuditLog(AuditLogEntry entry);

    // ───────── Spend dashboard reads (read-only, no audit rows) ─────────

    /**
     * entitlements {@code GET /internal/admin/spend?ownerType=&ownerId=} — an owner's GROSS
     * wallet spend by action off the consumptions ledger (lanes-only: USER = personal wallet,
     * ORG = the shared org wallet as a whole).
     */
    SpendReportView ownerSpend(String ownerType, UUID ownerId);

    /** entitlements {@code GET /internal/admin/spend/global} — platform-wide GROSS spend. */
    SpendReportView globalSpend();

    /**
     * billing {@code GET /internal/admin/wallet/summary?ownerType=&ownerId=} — an owner's exact
     * money picture: balance, money-in, refunds split by kind (consume-refund vs card clawback).
     */
    WalletSummaryView walletSummary(String ownerType, UUID ownerId);

    /** billing {@code GET /internal/admin/wallet/summary/global} — platform-wide money picture. */
    GlobalWalletSummaryView walletSummaryGlobal();

    /** billing {@code GET /internal/admin/wallet/flows} — loaded vs spent per rolling window. */
    MoneyFlowsView walletFlows();
}
