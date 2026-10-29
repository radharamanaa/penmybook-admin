package dev.bookforge.adminapi.api;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.DownstreamResult;
import dev.bookforge.adminapi.dto.ResolveRefundRequestBody;
import dev.bookforge.adminapi.dto.MarkPaidByHandBody;
import dev.bookforge.adminapi.dto.PreAdmitEntryView;
import dev.bookforge.adminapi.dto.PreAdmitView;
import dev.bookforge.adminapi.dto.WaitlistAdmitView;
import dev.bookforge.adminapi.dto.WaitlistConfigView;
import dev.bookforge.adminapi.dto.WaitlistEntryView;
import dev.bookforge.adminapi.dto.AccountHoldReleaseBody;
import dev.bookforge.adminapi.dto.AccountReactivateBody;
import dev.bookforge.adminapi.dto.AccountReactivatedView;
import dev.bookforge.adminapi.dto.AccountStateView;
import dev.bookforge.adminapi.dto.AdminActionResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.WalletUnfrozenView;
import dev.bookforge.adminapi.dto.OrgWalletStateView;
import dev.bookforge.adminapi.dto.AdminOtpStartRequest;
import dev.bookforge.adminapi.dto.AdminOtpStartResponse;
import dev.bookforge.adminapi.dto.AdminOtpVerifyRequest;
import dev.bookforge.adminapi.dto.AdminOtpVerifyResponse;
import dev.bookforge.adminapi.dto.GlobalSpendOverview;
import dev.bookforge.adminapi.dto.GrantCreditsReceiptView;
import dev.bookforge.adminapi.dto.MoneyFlowsView;
import dev.bookforge.adminapi.dto.GrantCreditsRequest;
import dev.bookforge.adminapi.dto.OrgPricingRequest;
import dev.bookforge.adminapi.dto.OrgPricingRuleView;
import dev.bookforge.adminapi.dto.OwnerSpendOverview;
import dev.bookforge.adminapi.dto.PaymentHealthView;
import dev.bookforge.adminapi.dto.PlatformAdminUpdateView;
import dev.bookforge.adminapi.dto.ForceResolveReversedBody;
import dev.bookforge.adminapi.dto.ForceResolveReversedView;
import dev.bookforge.adminapi.dto.RefundReceiptView;
import dev.bookforge.adminapi.dto.RefundRequest;
import dev.bookforge.adminapi.dto.SetPlatformAdminRequest;
import dev.bookforge.adminapi.dto.SpendByBooksResponse;
import dev.bookforge.adminapi.dto.UserSearchResultEntry;
import dev.bookforge.adminapi.dto.WalletBalanceView;
import dev.bookforge.adminapi.dto.WalletTopUpRequest;
import dev.bookforge.adminapi.service.AdminOtpService;
import dev.bookforge.adminapi.service.AdminService;
import dev.bookforge.adminapi.service.SpendOverviewService;
import dev.bookforge.adminapi.service.audit.AuditEventWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public REST surface for the PenMyBook admin portal (master plan §4.9). Every endpoint:
 *
 * <ul>
 *   <li>requires HS256 user JWT authentication (enforced by
 *       {@code SecurityConfig.securityFilterChain});</li>
 *   <li>is guarded by {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")} — non-admins are
 *       rejected with HTTP 403 by Spring Security, and the
 *       {@code SecurityConfig.AuditingAccessDeniedHandler} writes a {@code decision=DENY}
 *       row to {@code admin_api.audit_log} via {@link AuditEventWriter} so the rejected
 *       attempt is forensically recorded (T25 / R26);</li>
 *   <li>writes a {@code decision=ALLOW} row to {@code admin_api.audit_log} on success
 *       carrying the staff actor, the action, the resource (USER / ORG / PAYMENT), and the
 *       new/old value diff.</li>
 * </ul>
 *
 * <p>The 6 books-list / closed-stale / insufficient-funds READ endpoints (#6-#8) are wired but
 * intentionally return {@code 501 NOT_IMPLEMENTED} pending Phase 4 — book_curator must first
 * expose {@code /internal/admin/books?user_id=} and the closed-stale / insufficient-funds
 * queues. See {@code Phase 4 TODO} comments at each method.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    // ───────── Audit action labels (LOCKED — match V00X__create_audit_log.sql action enum) ─────────
    private static final String ACTION_WALLET_TOPUP = "WALLET_TOPUP";
    private static final String ACTION_REFUND = "REFUND";
    /** A refund call billing answered from an earlier receipt — no money moved this time. */
    private static final String ACTION_REFUND_REPLAY = "REFUND_REPLAY";
    /** audit_log.action is plain TEXT — a new action needs no migration. */
    private static final String ACTION_REFUND_REQUEST_RESOLVE = "REFUND_REQUEST_RESOLVE";
    private static final String ACTION_FORCE_RESOLVE_REVERSED = "FORCE_RESOLVE_REVERSED";
    private static final String ACTION_REFUND_MARK_PAID = "REFUND_MARK_PAID_BY_HAND";
    private static final String ACTION_GRANT_CREDITS = "GRANT_CREDITS";
    private static final String ACTION_SET_PLATFORM_ADMIN = "SET_PLATFORM_ADMIN";
    private static final String ACTION_UPSERT_ORG_PRICING = "UPSERT_ORG_PRICING";
    private static final String ACTION_UPSERT_GLOBAL_PRICING = "UPSERT_GLOBAL_PRICING";
    private static final String ACTION_ACCOUNT_REACTIVATE = "ACCOUNT_REACTIVATE";
    private static final String ACTION_WALLET_UNFREEZE = "WALLET_UNFREEZE";

    private static final String RESOURCE_USER = "USER";
    private static final String RESOURCE_PAYMENT = "PAYMENT";
    private static final String RESOURCE_REFUND_REQUEST = "REFUND_REQUEST";
    private static final String RESOURCE_ORG = "ORG";
    private static final String RESOURCE_PRICING = "PRICING";

    /** Phase-4 TODO marker — returned in the 501 body so the SPA can render a sane fallback. */
    private static final Map<String, String> PHASE_4_PENDING = Map.of(
            "error", "not_implemented",
            "code", "NOT_IMPLEMENTED",
            "phase", "Phase 4 — book_curator must first expose /internal/admin/books "
                    + "and closed-stale / insufficient-funds queue endpoints.");

    /** Action name the step-up token must be bound to for a wallet-money grant. */
    private static final String STEP_UP_ACTION_GRANT_CREDIT = "grant_credit";
    /** Action name the step-up token must be bound to for a refund (B3). */
    private static final String STEP_UP_ACTION_REFUND = "refund";
    /** Approving or rejecting a user-submitted refund request. */
    private static final String STEP_UP_ACTION_RESOLVE_REFUND_REQUEST = "resolve_refund_request";
    /** Action name the step-up token must be bound to for an admin wallet top-up (H3). */
    private static final String STEP_UP_ACTION_WALLET_TOP_UP = "wallet_top_up";
    /** Reactivate a suspended account. Hash binds the target user only (no params). */
    private static final String STEP_UP_ACTION_UNSUSPEND = "unsuspend";
    /** Lift a wallet freeze. Hash is {ownerType, ownerId}; billing verifies and spends it. */
    private static final String STEP_UP_ACTION_WALLET_UNFREEZE = "wallet_unfreeze";
    /** Settle an approved request paid out by hand in Stripe. Hash {requestId, providerRefundId}. */
    private static final String STEP_UP_ACTION_REFUND_MARK_PAID = "refund_mark_paid";
    /** Grant or revoke platform admin (O-5). Hash binds the target user and {isPlatformAdmin}. */
    private static final String STEP_UP_ACTION_SET_PLATFORM_ADMIN = "set_platform_admin";
    private static final String OWNER_TYPE_USER = "USER";
    private static final String OWNER_TYPE_ORG = "ORG";
    /** Header carrying the single-use, action-bound step-up token (spec 05 §3). */
    private static final String STEP_UP_TOKEN_HEADER = "X-Step-Up-Token";
    private static final String REJECT_STEP_UP_REQUIRED = "step_up_required";
    /** Shown to staff when a payout timed out: retrying with a fresh OTP would refund twice. */
    static final String REFUND_OUTCOME_UNKNOWN_MESSAGE =
            "The refund may have gone through — check the payment before retrying.";

    /** Valid admin step-up actions an OTP challenge may be started for. */
    private static final Set<String> STEP_UP_ACTIONS =
            Set.of(STEP_UP_ACTION_GRANT_CREDIT, STEP_UP_ACTION_REFUND, STEP_UP_ACTION_WALLET_TOP_UP,
                   STEP_UP_ACTION_RESOLVE_REFUND_REQUEST, STEP_UP_ACTION_UNSUSPEND,
                   STEP_UP_ACTION_WALLET_UNFREEZE, STEP_UP_ACTION_REFUND_MARK_PAID,
                   STEP_UP_ACTION_SET_PLATFORM_ADMIN);

    private static final Set<String> REFUND_QUEUE_STATUSES = Set.of(
            "PENDING", "APPROVED", "REJECTED", "WITHDRAWN", "PAYING", "SETTLED");
    private static final int REFUND_QUEUE_MAX_LIMIT = 100;
    /** Billing's ceiling for one page of the unrecovered-payments list. */
    private static final int UNRECOVERED_PAYMENTS_LIMIT = 200;

    private final DownstreamClient downstream;
    private final AuditEventWriter auditEventWriter;
    private final AdminService adminService;
    private final AdminOtpService adminOtpService;
    private final SpendOverviewService spendOverviewService;

    public AdminController(DownstreamClient downstream, AuditEventWriter auditEventWriter,
                           AdminService adminService, AdminOtpService adminOtpService,
                           SpendOverviewService spendOverviewService) {
        this.downstream = downstream;
        this.auditEventWriter = auditEventWriter;
        this.adminService = adminService;
        this.adminOtpService = adminOtpService;
        this.spendOverviewService = spendOverviewService;
    }

    // ───────── 0. Admin step-up OTP (F2) ─────────

    /**
     * Start an OTP step-up challenge for the signed-in staff admin (F2). Delegates to entitlements
     * {@code POST /internal/otp/start}, which mints a 6-digit code (hashed at rest; in dev/pre-SES
     * also stored plaintext in {@code otp_challenges.dev_plaintext_code} for local retrieval) and
     * returns a {@code challengeId}. The SPA echoes the {@code challengeId} + the received code to
     * {@code /v1/admin/otp/verify}. The {@code action} + {@code targetUserId} + {@code params} are
     * bound into the eventual token's {@code paramsHash} so the authorisation cannot be replayed.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')} — only platform staff step up.
     */
    @PostMapping("/otp/start")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> otpStart(@Valid @RequestBody AdminOtpStartRequest body,
                                           @AuthenticationPrincipal Jwt jwt) {
        if (!STEP_UP_ACTIONS.contains(body.action())) {
            return badAction(body.action());
        }
        AdminOtpStartResponse response = adminOtpService.start(actorOf(jwt), emailOf(jwt),
                body.action(), body.targetUserId(), body.params());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Verify a submitted OTP code (F2). Delegates to entitlements {@code POST /internal/otp/verify}
     * to CHECK the code; on success admin-api mints its OWN single-use, action-bound step-up token
     * whose {@code paramsHash} is bound over (action, staffUserId, targetUserId, params). The SPA
     * sends the returned token as {@code X-Step-Up-Token} on the gated grant / refund write. A
     * wrong/expired/locked code returns {@code 200 {verified:false, failureReason}} (the SPA
     * re-prompts) — no token is minted.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @PostMapping("/otp/verify")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> otpVerify(@Valid @RequestBody AdminOtpVerifyRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        if (!STEP_UP_ACTIONS.contains(body.action())) {
            return badAction(body.action());
        }
        AdminOtpVerifyResponse response = adminOtpService.verify(actorOf(jwt), body.challengeId(),
                body.code(), body.action(), body.targetUserId(), body.params());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Object> badAction(String action) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "unsupported_action", "code", "UNSUPPORTED_ACTION", "action", action));
    }

    // ───────── 1. POST wallet top-up ─────────

    /**
     * Credit a user's wallet by the supplied number of cents. Delegates to billing
     * {@code POST /internal/wallet/credit}. Writes an {@code admin_api.audit_log} ALLOW row
     * with old (no prior balance fetched — kept null to avoid a second downstream round-trip)
     * and new balance.
     *
     * <p>A top-up mints spendable MONEY into the user's wallet exactly like {@link
     * #grantCredits}, so — like grant — it is <strong>step-up (OTP) gated</strong> (H3). It routes
     * through {@link AdminService#walletTopUp} → the same {@code gate(...)} the grant / refund
     * writes use: the {@code X-Step-Up-Token} is verified for signature, action
     * ({@code wallet_top_up}) and expiry, single-use-consumed, and its {@code paramsHash} is bound
     * over {@code amountCents} so a token authorised for one amount cannot be replayed for another.
     * A missing token is rejected {@code 403 step_up_required}; an invalid / mismatched / expired /
     * replayed token is rejected {@code 403 step_up_invalid}.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. A non-admin caller is rejected with
     * HTTP 403 by {@code SecurityConfig.AuditingAccessDeniedHandler} (before this body runs), which
     * also writes a {@code decision=DENY} audit row capturing the attempted action for forensics.
     */
    @PostMapping("/users/{userId}/wallet/top-up")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> walletTopUp(@PathVariable UUID userId,
                                              @Valid @RequestBody WalletTopUpRequest body,
                                              @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                              String stepUpToken,
                                              @AuthenticationPrincipal Jwt jwt,
                                              HttpServletRequest request) {
        // Params bound into the step-up token's paramsHash — amountCents only, matching what
        // AdminParamsHasher recomputes server-side (mirrors grant-credits).
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", body.amountCents());

        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_WALLET_TOP_UP, stepUpToken, params);
        AdminService.WalletTopUpOutcome outcome =
                adminService.walletTopUp(command, body.amountCents(), body.reason());
        if (!outcome.success()) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_WALLET_TOP_UP, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("amountCents", body.amountCents());
        newValue.put("reason", body.reason());
        newValue.put("balanceAfterCents", outcome.balanceCents());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_WALLET_TOPUP, RESOURCE_USER,
                userId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(new WalletBalanceView(userId, outcome.balanceCents()));
    }

    // ───────── 2. POST refund ─────────

    /**
     * Refund a previous payment in whole or in part. Idempotent on {@code paymentId} per AD8 /
     * G14 — a duplicate POST returns the original receipt with {@code idempotentReplay=true}
     * and only one audit row is written (admin-api re-records the action, but billing only
     * debits once).
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    /**
     * The staff refund-request queue. A READ — no step-up, no audit row.
     *
     * <p>Returns an envelope, never a bare list. Billing going dark used to render as
     * {@code 200 []}, indistinguishable from a clear queue.
     */
    @GetMapping("/refund-requests")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> listRefundRequests(
            @RequestParam(name = "status", defaultValue = "PENDING") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (!REFUND_QUEUE_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_status",
                    "code", "INVALID_STATUS",
                    "status", status));
        }
        if (limit < 1 || limit > REFUND_QUEUE_MAX_LIMIT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_limit",
                    "code", "INVALID_LIMIT",
                    "limit", limit));
        }
        return ResponseEntity.ok(downstream.listRefundRequests(status, limit));
    }

    /**
     * Approve or reject a user's refund request. ONLY an admin can approve, and the ADMIN sets the
     * final amount — the user's figure is a request, not something the system executes blindly.
     *
     * <p>Step-up gated: the token's paramsHash is bound over {requestId, decision, amountCents},
     * the same map AdminParamsHasher recomputes server-side, so a token authorised to return $20
     * cannot be replayed to return $500. {@code note} is required on the write but is not in that
     * hash. Billing independently clamps the amount to the live refundable balance, so a typo
     * cannot return bonus credit — or money never paid — to a card.
     */
    @PostMapping("/refund-requests/{requestId}/resolve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> resolveRefundRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody ResolveRefundRequestBody body,
            @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false) String stepUpToken,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId.toString());
        params.put("decision", body.decision());
        params.put("amountCents", body.amountCents());
        params.put("note", body.note());

        AdminCommand command = new AdminCommand(actorOf(jwt), null,
                STEP_UP_ACTION_RESOLVE_REFUND_REQUEST, stepUpToken, params);
        AdminService.ResolveRefundOutcome outcome = adminService.resolveRefundRequest(command);
        if (!outcome.success()) {
            auditEventWriter.writeDeny(actorOf(jwt), ACTION_REFUND_REQUEST_RESOLVE,
                    RESOURCE_REFUND_REQUEST, requestId.toString(), params, clientIp(request));
            if (outcome.rejectionReason() == null) {
                // Billing may already have moved money. Never offer a retry — a fresh OTP is a
                // new refund. The operator must refresh and read the row's status.
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", "downstream_unconfirmed",
                        "code", "DOWNSTREAM_UNCONFIRMED",
                        "message", "Billing did not confirm. Refresh and check the status."));
            }
            return stepUpRejectionResponse(outcome.rejectionReason(),
                    STEP_UP_ACTION_RESOLVE_REFUND_REQUEST, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("requestId", requestId.toString());
        newValue.put("decision", body.decision());
        newValue.put("status", outcome.status());
        newValue.put("approvedAmountCents", outcome.approvedAmountCents());
        newValue.put("refundedCents", outcome.refundedCents());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_REFUND_REQUEST_RESOLVE,
                RESOURCE_REFUND_REQUEST, requestId.toString(), null, newValue, clientIp(request));
        Map<String, Object> bodyOut = new LinkedHashMap<>();
        bodyOut.put("resolved", true);
        bodyOut.put("requestId", requestId.toString());
        bodyOut.put("status", outcome.status());
        bodyOut.put("approvedAmountCents", outcome.approvedAmountCents());
        bodyOut.put("refundedCents", outcome.refundedCents());
        return ResponseEntity.ok(bodyOut);
    }

    /**
     * Settle an APPROVED refund request that staff paid out by hand in the Stripe Dashboard (an org
     * request has no payout path). Billing checks the Stripe refund id is one its webhook already
     * recorded for a payment of the request's owner, used by no other request, and settles it.
     *
     * <p>Step-up gated ({@code refund_mark_paid}); the hash binds {requestId, providerRefundId} with
     * the request's owner as the target, and billing spends the token. Billing's refusals (the
     * webhook has not landed, the refund id is already used, a note is needed because Stripe
     * refunded less than approved) are returned with billing's status and message verbatim.
     */
    @PostMapping("/refund-requests/{requestId}/mark-paid-by-hand")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> markRefundPaidByHand(
            @PathVariable UUID requestId,
            @Valid @RequestBody MarkPaidByHandBody body,
            @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false) String stepUpToken,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId.toString());
        params.put("providerRefundId", body.providerRefundId().trim());
        String note = body.note() == null || body.note().isBlank() ? null : body.note().trim();

        AdminCommand command = new AdminCommand(actorOf(jwt), body.ownerId(),
                STEP_UP_ACTION_REFUND_MARK_PAID, stepUpToken, params);
        AdminService.MarkPaidByHandOutcome outcome = adminService.markRefundPaidByHand(command, note);
        if (!outcome.success()) {
            Map<String, Object> denied = new LinkedHashMap<>(params);
            denied.put("note", note);
            auditEventWriter.writeDeny(actorOf(jwt), ACTION_REFUND_MARK_PAID,
                    RESOURCE_REFUND_REQUEST, requestId.toString(), denied, clientIp(request));
            if (outcome.rejectionReason() != null) {
                return stepUpRejectionResponse(outcome.rejectionReason(),
                        STEP_UP_ACTION_REFUND_MARK_PAID, params);
            }
            if (outcome.refusedStatus() > 0) {
                Map<String, Object> refused = new LinkedHashMap<>();
                refused.put("error", "refund_mark_paid_refused");
                refused.put("code", outcome.refusedCode() == null
                        ? "REFUND_MARK_PAID_REFUSED" : outcome.refusedCode());
                refused.put("message", outcome.refusedMessage() == null
                        ? "Billing refused to mark this request paid." : outcome.refusedMessage());
                return ResponseEntity.status(outcome.refusedStatus()).body(refused);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "downstream_unconfirmed",
                    "code", "DOWNSTREAM_UNCONFIRMED",
                    "message", "Billing did not confirm. Refresh and check the status."));
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("requestId", requestId.toString());
        newValue.put("ownerId", body.ownerId().toString());
        newValue.put("providerRefundId", params.get("providerRefundId"));
        newValue.put("note", note);
        if (outcome.request() != null) {
            newValue.put("status", outcome.request().status());
            newValue.put("refundedCents", outcome.request().refundedCents());
        }
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_REFUND_MARK_PAID,
                RESOURCE_REFUND_REQUEST, requestId.toString(), null, newValue, clientIp(request));
        Map<String, Object> bodyOut = new LinkedHashMap<>();
        bodyOut.put("markedPaid", true);
        bodyOut.put("request", outcome.request());
        return ResponseEntity.ok(bodyOut);
    }

    /**
     * Payments a refund or dispute could not fully take back from the wallet, because the money
     * was already spent. A READ: no step-up, no audit row. Envelope, never a bare list, so a
     * billing outage cannot look like nothing is unrecovered.
     */
    @GetMapping("/payments/unrecovered")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> listUnrecoveredPayments() {
        return ResponseEntity.ok(downstream.listUnrecoveredPayments(UNRECOVERED_PAYMENTS_LIMIT));
    }

    @PostMapping("/users/{userId}/refund")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> refund(@PathVariable UUID userId,
                                         @Valid @RequestBody RefundRequest body,
                                         @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                         String stepUpToken,
                                         @AuthenticationPrincipal Jwt jwt,
                                         HttpServletRequest request) {
        // A refund pulls MONEY back out of a user's wallet, so — like grant — it is step-up gated
        // (B3). The token's paramsHash is bound over {paymentId, refundAmountCents} and, when
        // present, refundRequestId — the SAME map AdminParamsHasher recomputes server-side, so a
        // token authorised for a bare payout cannot be replayed onto a request payout. The refund
        // is routed through the gated AdminService.refund → runWrite(REFUND) path.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", body.paymentId().toString());
        params.put("refundAmountCents", body.amountCents());
        if (body.refundRequestId() != null) {
            params.put("refundRequestId", body.refundRequestId().toString());
        }

        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_REFUND, stepUpToken, params);
        AdminService.RefundOutcome outcome = adminService.refund(command);
        if (!outcome.success()) {
            if (AdminService.REFUND_OUTCOME_UNKNOWN.equals(outcome.rejectionReason())) {
                // The card refund may have landed: leave a trace staff can match to Stripe.
                Map<String, Object> attempted = new LinkedHashMap<>(params);
                attempted.put("reason", "outcome_unknown");
                auditEventWriter.writeDeny(actorOf(jwt), ACTION_REFUND, RESOURCE_PAYMENT,
                        body.paymentId().toString(), attempted, clientIp(request));
            }
            return refundRejection(outcome, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("paymentId", body.paymentId().toString());
        newValue.put("requestedCents", outcome.requestedCents());
        newValue.put("walletDebitedCents", outcome.walletDebitedCents());
        newValue.put("reason", body.reason());
        newValue.put("idempotentReplay", outcome.idempotentReplay());
        if (body.refundRequestId() != null) {
            newValue.put("refundRequestId", body.refundRequestId().toString());
        }
        // A replay moved no money; record it as one so it never reads as a second refund.
        auditEventWriter.writeAllow(actorOf(jwt),
                outcome.idempotentReplay() ? ACTION_REFUND_REPLAY : ACTION_REFUND, RESOURCE_PAYMENT,
                body.paymentId().toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(new RefundReceiptView(body.paymentId(),
                outcome.requestedCents(), outcome.walletDebitedCents(),
                outcome.auditEntryId(), outcome.idempotentReplay()));
    }

    /**
     * C-17: restore wallet after Stripe reversed the refund. Step-up action is {@code refund};
     * paramsHash is {@code {paymentId, forceResolve=true, note}} so a payout token cannot match.
     * Webhooks still do not auto-restore.
     */
    @PostMapping("/users/{userId}/payments/{paymentId}/force-resolve-reversed")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> forceResolveReversed(
            @PathVariable UUID userId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ForceResolveReversedBody body,
            @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false) String stepUpToken,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId.toString());
        params.put("forceResolve", true);
        params.put("note", body.note());

        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_REFUND, stepUpToken, params);
        AdminService.ForceResolveOutcome outcome = adminService.forceResolveReversed(command);
        if (!outcome.success()) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_REFUND, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("paymentId", paymentId.toString());
        newValue.put("restoredCents", outcome.restoredCents());
        newValue.put("note", body.note());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_FORCE_RESOLVE_REVERSED, RESOURCE_PAYMENT,
                paymentId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(new ForceResolveReversedView(outcome.paymentId(),
                outcome.restoredCents()));
    }

    /**
     * Map a non-success refund {@link AdminService.RefundOutcome} to an HTTP response. Mirrors
     * {@link #stepUpRejection}: a step-up rejection is a {@code 403} with {@code {code[, action,
     * params]}}; a {@code null} reason means billing rolled the refund back → {@code 502}.
     */
    private ResponseEntity<Object> refundRejection(AdminService.RefundOutcome outcome,
                                                   Map<String, Object> params) {
        if (AdminService.REFUND_OUTCOME_UNKNOWN.equals(outcome.rejectionReason())) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                    "error", AdminService.REFUND_OUTCOME_UNKNOWN,
                    "code", "REFUND_OUTCOME_UNKNOWN",
                    "message", REFUND_OUTCOME_UNKNOWN_MESSAGE));
        }
        return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_REFUND, params);
    }

    /**
     * Shared mapping of a non-success step-up outcome to its HTTP response, used by every gated
     * money write (grant / refund / wallet top-up). A {@code null} reason means the downstream
     * rolled the action back → {@code 502 Bad Gateway} (no phantom success). Otherwise a
     * {@code 403} with the mesh-standard body {@code {code[, action, params]}} — the {@code action}
     * + {@code params} are echoed only for {@code step_up_required} so the SPA knows which action to
     * start an OTP challenge for.
     */
    private ResponseEntity<Object> stepUpRejectionResponse(String reason, String action,
                                                           Map<String, Object> params) {
        if (reason == null) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "downstream_rejected", "code", "DOWNSTREAM_REJECTED"));
        }
        Map<String, Object> bodyOut = new LinkedHashMap<>();
        bodyOut.put("code", reason);
        if (REJECT_STEP_UP_REQUIRED.equals(reason)) {
            bodyOut.put("action", action);
            bodyOut.put("params", params);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(bodyOut);
    }

    // ───────── 3. POST grant wallet money ─────────

    /**
     * Grant the target user goodwill MONEY (cents) into their personal wallet (an
     * {@code ADMIN_CREDIT} wallet credit). Unified-wallet money model (D7 / P12 / UF-04): this is
     * no longer a chapter-credit count — it mints spendable money, so it is <strong>step-up (OTP)
     * gated</strong>.
     *
     * <p>The grant is routed through {@link AdminService#grantCredit(AdminCommand)} — the SAME
     * {@code runWrite(...)} mechanism the refund / suspend writes use. The step-up token (from the
     * {@code X-Step-Up-Token} header) is verified for signature, action ({@code grant_credit}) and
     * expiry, single-use-consumed, and its {@code paramsHash} is bound over the canonical hash of
     * THIS request's params — which include {@code amountCents} — so the token cannot be replayed
     * to authorise a different amount. A missing token is rejected {@code 403 step_up_required};
     * an invalid / mismatched / expired / replayed token is rejected {@code 403 step_up_invalid}.
     *
     * <p><strong>IMPORTANT:</strong> a grant does NOT change {@code has_ever_paid} (master plan §3
     * N2 / D11). {@code has_ever_paid} flips only when billing records a WALLET-source consume from
     * a Stripe Checkout success; the entitlements {@code POST /internal/admin/grant_credit} wallet
     * credit never touches it.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @PostMapping("/users/{userId}/grant-credits")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> grantCredits(@PathVariable UUID userId,
                                               @Valid @RequestBody GrantCreditsRequest body,
                                               @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                               String stepUpToken,
                                               @AuthenticationPrincipal Jwt jwt,
                                               HttpServletRequest request) {
        // Params bound into the step-up token's paramsHash — MUST match AdminParamsHasher's view
        // of the same map (amountCents only) so the verify side recomputes byte-for-byte.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", body.amountCents());

        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_GRANT_CREDIT, stepUpToken, params);
        AdminActionResult result = adminService.grantCredit(command);
        if (!result.success()) {
            return stepUpRejection(result, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("amountCents", body.amountCents());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_GRANT_CREDITS, RESOURCE_USER,
                userId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(
                new GrantCreditsReceiptView(userId, body.amountCents(), result.auditEntryId()));
    }

    /**
     * Map a non-success {@link AdminActionResult} from {@code runWrite} to an HTTP response. A
     * step-up rejection ({@code step_up_required} / {@code step_up_invalid}) is a {@code 403} with
     * the mesh-standard body {@code {code, action, params}} (matches the org step-up shape in
     * contracts/internal-api.md). A {@code null} rejection reason means the downstream rolled the
     * action back (e.g. its same-tx audit insert failed) — surfaced as {@code 502 Bad Gateway} so
     * the caller does not see a phantom success.
     */
    private ResponseEntity<Object> stepUpRejection(AdminActionResult result,
                                                   Map<String, Object> params) {
        return stepUpRejectionResponse(result.rejectionReason(), STEP_UP_ACTION_GRANT_CREDIT, params);
    }

    // ───────── 3b. Account holds: read, reactivate, unfreeze ─────────

    /**
     * Why a user is held: account status plus the wallet freeze, its reason and any money a refund
     * or dispute could not take back. A READ — no step-up, no audit row.
     */
    @GetMapping("/users/{userId}/account-state")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public AccountStateView accountState(@PathVariable UUID userId) {
        return adminService.accountState(userId);
    }

    /**
     * Reactivate a suspended account (a chargeback hold or a content review). Step-up gated; the
     * hash binds the target user only ({@code acknowledgeWalletUnknown} and
     * {@code resetContentStrikes} stay out of it, like the note, and are audited). Entitlements
     * refuses any account that is not suspended (banned, waitlisted, soft-deleted, active) and the
     * caller gets {@code 409 not_suspended}; it also refuses (409, its code and message relayed)
     * while the wallet is frozen by a dispute or refund ({@code wallet_frozen_by_payment_hold}) and
     * when billing did not answer unless staff acknowledged it ({@code wallet_status_unknown}).
     */
    @PostMapping("/users/{userId}/unsuspend")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> unsuspend(@PathVariable UUID userId,
                                            @Valid @RequestBody AccountReactivateBody body,
                                            @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                            String stepUpToken,
                                            @AuthenticationPrincipal Jwt jwt,
                                            HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_UNSUSPEND, stepUpToken, params);
        boolean acknowledgeWalletUnknown = Boolean.TRUE.equals(body.acknowledgeWalletUnknown());
        boolean resetContentStrikes = Boolean.TRUE.equals(body.resetContentStrikes());
        AdminService.UnsuspendOutcome outcome = adminService.unsuspend(
                command, acknowledgeWalletUnknown, resetContentStrikes, body.note());
        if (outcome.refusalCode() != null) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("error", outcome.refusalCode());
            refused.put("code", outcome.refusalCode());
            refused.put("message", outcome.refusalMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(refused);
        }
        if (!outcome.success() && outcome.rejectionReason() == null && outcome.accountStatus() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "not_suspended", "code", "NOT_SUSPENDED",
                    "accountStatus", outcome.accountStatus()));
        }
        if (!outcome.success()) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_UNSUSPEND, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("accountStatus", outcome.accountStatus());
        newValue.put("note", body.note());
        newValue.put("acknowledgeWalletUnknown", acknowledgeWalletUnknown);
        newValue.put("resetContentStrikes", resetContentStrikes);
        if (outcome.contentStrikesReset() != null) {
            newValue.put("contentStrikesReset", outcome.contentStrikesReset());
        }
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_ACCOUNT_REACTIVATE, RESOURCE_USER,
                userId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(new AccountReactivatedView(userId, outcome.accountStatus(),
                outcome.auditEntryId(), outcome.contentStrikesReset(), outcome.contentStrikesResetError()));
    }

    /**
     * Lift the user's wallet freeze. Step-up gated; the hash binds {@code {ownerType, ownerId}} and
     * billing verifies and spends the token. Lifting a freeze does not settle unrecovered money —
     * the account-state read shows it so staff decide with it on screen.
     */
    @PostMapping("/users/{userId}/wallet/unfreeze")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> unfreezeWallet(@PathVariable UUID userId,
                                                 @Valid @RequestBody AccountHoldReleaseBody body,
                                                 @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                                 String stepUpToken,
                                                 @AuthenticationPrincipal Jwt jwt,
                                                 HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownerType", OWNER_TYPE_USER);
        params.put("ownerId", userId.toString());
        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_WALLET_UNFREEZE, stepUpToken, params);
        AdminService.WalletUnfreezeOutcome outcome = adminService.unfreezeWallet(command, body.note());
        if (!outcome.success()) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_WALLET_UNFREEZE, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("lifted", outcome.lifted());
        newValue.put("note", body.note());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_WALLET_UNFREEZE, RESOURCE_USER,
                userId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(new WalletUnfrozenView(userId, outcome.lifted()));
    }

    /** An org wallet's freeze, reason and unrecovered money. A READ — no step-up, no audit row. */
    @GetMapping("/orgs/{orgId}/wallet-state")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OrgWalletStateView orgWalletState(@PathVariable UUID orgId) {
        return adminService.orgWalletState(orgId);
    }

    /**
     * Lift an org wallet freeze (a chargeback on an org top-up). Same {@code wallet_unfreeze}
     * step-up as a user wallet; the hash binds {@code {ownerType: ORG, ownerId: orgId}} with the org
     * as the target, so a user-wallet approval cannot lift an org freeze or the reverse. Members
     * suspended by the hold are reactivated one by one.
     */
    @PostMapping("/orgs/{orgId}/wallet/unfreeze")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> unfreezeOrgWallet(@PathVariable UUID orgId,
                                                    @Valid @RequestBody AccountHoldReleaseBody body,
                                                    @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                                    String stepUpToken,
                                                    @AuthenticationPrincipal Jwt jwt,
                                                    HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownerType", OWNER_TYPE_ORG);
        params.put("ownerId", orgId.toString());
        AdminCommand command = new AdminCommand(actorOf(jwt), orgId,
                STEP_UP_ACTION_WALLET_UNFREEZE, stepUpToken, params);
        AdminService.WalletUnfreezeOutcome outcome = adminService.unfreezeWallet(command, body.note());
        if (!outcome.success()) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_WALLET_UNFREEZE, params);
        }

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("lifted", outcome.lifted());
        newValue.put("note", body.note());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_WALLET_UNFREEZE, RESOURCE_ORG,
                orgId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(Map.of("orgId", orgId, "lifted", outcome.lifted()));
    }

    // ───────── 4. PATCH set platform-admin flag ─────────

    /**
     * Flip the {@code is_platform_admin} boolean on the target user (AD10). Delegates to
     * entitlements {@code PATCH /internal/admin/users/{userId}}.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')} — admin promotion is a platform-only
     * decision; org admins / owners MUST NOT be able to promote themselves.
     */
    @PatchMapping("/users/{userId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Object> setPlatformAdmin(@PathVariable UUID userId,
                                                   @Valid @RequestBody SetPlatformAdminRequest body,
                                                   @RequestHeader(value = STEP_UP_TOKEN_HEADER, required = false)
                                                   String stepUpToken,
                                                   @AuthenticationPrincipal Jwt jwt,
                                                   HttpServletRequest request) {
        // O-5: the hash binds the target user and the new value — a token approved to revoke
        // cannot grant, and one approved for another user is refused.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("isPlatformAdmin", body.isPlatformAdmin());
        AdminCommand command = new AdminCommand(actorOf(jwt), userId,
                STEP_UP_ACTION_SET_PLATFORM_ADMIN, stepUpToken, params);
        AdminService.PlatformAdminOutcome outcome = adminService.setPlatformAdmin(command, body.isPlatformAdmin());
        if (outcome.updated() == null) {
            return stepUpRejectionResponse(outcome.rejectionReason(), STEP_UP_ACTION_SET_PLATFORM_ADMIN, params);
        }
        PlatformAdminUpdateView updated = outcome.updated();
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("isPlatformAdmin", updated.isPlatformAdmin());
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_SET_PLATFORM_ADMIN, RESOURCE_USER,
                userId.toString(), null, newValue, clientIp(request));
        return ResponseEntity.ok(updated);
    }

    // ───────── 5. GET user search ─────────

    /**
     * Fuzzy email-prefix search for users; delegates to entitlements
     * {@code GET /internal/admin/users/search}. Read-only — no audit row.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @GetMapping("/users/search")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<UserSearchResultEntry> searchUsers(@RequestParam("email") String emailQuery,
                                                   @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return downstream.searchUsers(emailQuery, Math.min(Math.max(limit, 1), 100));
    }

    // ───────── Spend dashboard (read-only — no audit rows, mirroring searchUsers) ─────────

    /**
     * A user's exact money 360: net/gross spend by action (entitlements consumptions ledger),
     * wallet balance, money-in and refund splits (billing ledgers). All figures exact cents.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @GetMapping("/users/{userId}/spend")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OwnerSpendOverview userSpend(@PathVariable UUID userId) {
        return spendOverviewService.userOverview(userId);
    }

    /**
     * An org's exact money 360 (the shared ORG wallet) including the per-member spend split
     * merged with the org roster — zero-spend members included, past-member spend retained.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @GetMapping("/orgs/{orgId}/spend")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OwnerSpendOverview orgSpend(@PathVariable UUID orgId) {
        return spendOverviewService.orgOverview(orgId);
    }

    /**
     * Platform-wide money tiles: held balance (split USER/ORG lanes), gross/net spend,
     * money-in, refunds, Stripe cash. Exact cents across every wallet.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @GetMapping("/spend/global")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public GlobalSpendOverview globalSpend() {
        return spendOverviewService.globalOverview();
    }

    /**
     * Money loaded vs spent per rolling window (week / month / year / all time) — both
     * figures from billing's single transaction ledger, exact cents.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @GetMapping("/spend/flows")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public MoneyFlowsView spendFlows() {
        return spendOverviewService.moneyFlows();
    }

    /**
     * Payment-integration health (stuck refunds, uncredited/abandoned top-ups, webhook recency).
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @GetMapping("/payments/health")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public PaymentHealthView paymentsHealth() {
        return spendOverviewService.paymentsHealth();
    }

    /**
     * Per-book GROSS wallet charges (pre-refund) for a batch of book ids. Used by the
     * Analytics COGS vs charges table. Fail-soft downstream → empty list (zeros in UI).
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}. Read-only — no audit row.
     */
    @PostMapping("/spend/by-books")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public SpendByBooksResponse spendByBooks(@RequestBody List<String> bookIds) {
        return spendOverviewService.chargesByBooks(bookIds);
    }

    // ───────── 6. GET books for user ─────────

    /**
     * <strong>Phase 4 TODO:</strong> book_curator must first expose
     * {@code GET /internal/admin/books?user_id=}. Until then this endpoint returns
     * {@code 501 NOT_IMPLEMENTED} so the SPA can render a placeholder.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @GetMapping("/users/{userId}/books")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> userBooks(@PathVariable UUID userId) {
        // Suppress unused-parameter warning — the userId is part of the public surface even
        // though Phase 3b cannot honour it.
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(PHASE_4_PENDING);
    }

    // ───────── 7. GET closed-stale queue ─────────

    /**
     * <strong>Phase 4 TODO:</strong> book_curator must first expose a closed-stale queue
     * endpoint. Until then returns {@code 501 NOT_IMPLEMENTED}.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @GetMapping("/closed-stale-queue")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> closedStaleQueue(
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        if (limit <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(PHASE_4_PENDING);
    }

    // ───────── 8. GET insufficient-funds queue ─────────

    /**
     * <strong>Phase 4 TODO:</strong> book_curator must first expose an insufficient-funds queue
     * endpoint. Until then returns {@code 501 NOT_IMPLEMENTED}.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')}.
     */
    @GetMapping("/insufficient-funds-queue")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> insufficientFundsQueue(
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        if (limit <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(PHASE_4_PENDING);
    }

    // ───────── 9. POST per-org pricing override ─────────

    /**
     * Upsert a per-org pricing override (R26 / P-OrgPrice). Delegates to entitlements
     * {@code POST /internal/pricing/org-override}. The persisted row is returned including the
     * prior {@code valueCents} (null on first insert) so the audit row records both halves of
     * the diff for forensic review.
     *
     * <p>Authorisation: {@code hasRole('PLATFORM_ADMIN')} — pricing is a contractual decision
     * and MUST be platform-mediated; org admins / owners are rejected with HTTP 403 and a
     * {@code decision=DENY} audit row (T25 enforces this).
     */
    @PostMapping("/orgs/{orgId}/pricing")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OrgPricingRuleView upsertOrgPricing(@PathVariable UUID orgId,
                                               @Valid @RequestBody OrgPricingRequest body,
                                               @AuthenticationPrincipal Jwt jwt,
                                               HttpServletRequest request) {
        requireCategoryForChapter(body);
        OrgPricingRuleView persisted = downstream.upsertOrgPricing(
                orgId, body.action(), body.category(), body.valueCents());
        Map<String, Object> oldValue = null;
        if (persisted.previousValueCents() != null) {
            oldValue = new LinkedHashMap<>();
            oldValue.put("action", body.action());
            oldValue.put("valueCents", persisted.previousValueCents());
            if (body.category() != null) {
                oldValue.put("category", body.category());
            }
        }
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("action", body.action());
        newValue.put("valueCents", persisted.valueCents());
        if (body.category() != null) {
            newValue.put("category", body.category());
        }
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_UPSERT_ORG_PRICING, RESOURCE_ORG,
                orgId.toString(), oldValue, newValue, clientIp(request));
        return persisted;
    }

    // ───────── 9b. Waitlist review queue (admin control plane D11) ─────────

    /** The FIFO waitlist queue — who signed up while waitlist mode was on, oldest first. */
    @GetMapping("/waitlist")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<WaitlistEntryView> waitlistQueue(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return downstream.waitlistQueue(Math.max(1, Math.min(limit, 200)));
    }

    /**
     * Approve a waitlisted user (WAITLISTED → ACTIVE), consuming one weekly slot (admin control
     * plane D11). NOT a money action — no OTP; the admin login is the gate. The result reports
     * {@code admitted=false, reason="weekly_limit_reached"} when the week's slots are gone (the
     * operator must raise the limit) — surfaced as 200, an expected operational state. Only a real
     * admission is audited as ALLOW.
     */
    @PostMapping("/waitlist/{userId}/admit")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public WaitlistAdmitView waitlistAdmit(@PathVariable UUID userId,
                                           @AuthenticationPrincipal Jwt jwt,
                                           HttpServletRequest request) {
        WaitlistAdmitView result = downstream.waitlistAdmit(userId, actorOf(jwt));
        if (result.admitted()) {
            auditEventWriter.writeAllow(actorOf(jwt), "WAITLIST_ADMIT", RESOURCE_USER,
                    userId.toString(),
                    Map.of("account_status", "WAITLISTED"),
                    Map.of("account_status", "ACTIVE"),
                    clientIp(request));
        }
        return result;
    }

    /** Current closed-beta waitlist config + this-week usage (admin "Moderation & access" panel). */
    @GetMapping("/waitlist/config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public WaitlistConfigView waitlistConfig() {
        return downstream.waitlistConfig();
    }

    /** Update the waitlist config (enable flag and/or weekly approval limit). Audited old→new. */
    @PostMapping("/waitlist/config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public WaitlistConfigView updateWaitlistConfig(@RequestBody WaitlistConfigUpdateRequest body,
                                                   @AuthenticationPrincipal Jwt jwt,
                                                   HttpServletRequest request) {
        if (body.weeklyApprovalLimit() != null && body.weeklyApprovalLimit() < 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "weeklyApprovalLimit must be >= 0");
        }
        WaitlistConfigView before = downstream.waitlistConfig();
        WaitlistConfigView after = downstream.updateWaitlistConfig(
                body.enabled(), body.weeklyApprovalLimit(), emailOf(jwt));
        auditEventWriter.writeAllow(actorOf(jwt), "WAITLIST_CONFIG", RESOURCE_USER, "waitlist",
                Map.of("enabled", before.enabled(), "weeklyApprovalLimit", before.weeklyApprovalLimit()),
                Map.of("enabled", after.enabled(), "weeklyApprovalLimit", after.weeklyApprovalLimit()),
                clientIp(request));
        return after;
    }

    /** Partial waitlist-config update body — null fields are left unchanged. */
    public record WaitlistConfigUpdateRequest(Boolean enabled, Integer weeklyApprovalLimit) {
    }

    // ───────── 9c. Pre-admit by email (2026-09-03) ─────────

    private static final String ACTION_PRE_ADMIT_EMAIL = "PRE_ADMIT_EMAIL";

    /** Body for {@code POST /waitlist/pre-admit}. */
    public record PreAdmitRequest(@jakarta.validation.constraints.NotBlank String email) {
    }

    /**
     * Pre-approve an email address: the future signup lands ACTIVE, one weekly slot is claimed
     * now, and the invitee is emailed. Rejections come back 200 with a reason (same shape as
     * admit). Only an accepted pre-admit is audited as ALLOW.
     */
    @PostMapping("/waitlist/pre-admit")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public PreAdmitView preAdmitEmail(@Valid @RequestBody PreAdmitRequest body,
                                      @AuthenticationPrincipal Jwt jwt,
                                      HttpServletRequest request) {
        String email = body.email().trim().toLowerCase();
        PreAdmitView result = downstream.preAdmitEmail(email, actorOf(jwt));
        if (result.admitted()) {
            auditEventWriter.writeAllow(actorOf(jwt), ACTION_PRE_ADMIT_EMAIL, RESOURCE_USER, email,
                    null,
                    Map.of("email", email, "pre_admitted", true),
                    clientIp(request));
        }
        return result;
    }

    /** Pending pre-admits (unconsumed, unexpired), oldest first. */
    @GetMapping("/waitlist/pre-admits")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<PreAdmitEntryView> listPreAdmits() {
        return downstream.listPreAdmits();
    }

    // ───────── 10. POST global pricing override ─────────

    /**
     * Current GLOBAL price (cents) per priced action — the portal's read-back so an operator can
     * see the live price after a change. Delegates to entitlements
     * {@code GET /internal/pricing/active-by-action}.
     */
    @GetMapping("/pricing")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Map<String, Long> currentPricing() {
        return downstream.currentPricing();
    }

    /**
     * Replace the GLOBAL default price for an action (admin control plane D5/D13 — e.g.
     * {@code PUBLISH_DIGITAL} at 0 cents = free digital PDF). Delegates to entitlements
     * {@code POST /internal/pricing/global-override}; per-org overrides keep winning over
     * the new global value. Audited with the old→new diff like the per-org flow.
     */
    @PostMapping("/pricing")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public OrgPricingRuleView upsertGlobalPricing(@Valid @RequestBody OrgPricingRequest body,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  HttpServletRequest request) {
        requireCategoryForChapter(body);
        OrgPricingRuleView persisted = downstream.upsertGlobalPricing(
                body.action(), body.category(), body.valueCents());
        Map<String, Object> oldValue = null;
        if (persisted.previousValueCents() != null) {
            oldValue = new LinkedHashMap<>();
            oldValue.put("action", body.action());
            oldValue.put("valueCents", persisted.previousValueCents());
            if (body.category() != null) {
                oldValue.put("category", body.category());
            }
        }
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("action", body.action());
        newValue.put("valueCents", persisted.valueCents());
        if (body.category() != null) {
            newValue.put("category", body.category());
        }
        auditEventWriter.writeAllow(actorOf(jwt), ACTION_UPSERT_GLOBAL_PRICING, RESOURCE_PRICING,
                "global", oldValue, newValue, clientIp(request));
        return persisted;
    }

    /** CHAPTER_GENERATE overrides must name a genre so they hit per_subtopic_* rows. */
    private static void requireCategoryForChapter(OrgPricingRequest body) {
        if ("CHAPTER_GENERATE".equals(body.action())
                && (body.category() == null || body.category().isBlank())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "category is required when action is CHAPTER_GENERATE");
        }
    }

    // ───────── Helpers ─────────

    /**
     * The staff admin's email from the book_admin token's {@code email} claim (D16/D17) — the
     * OTP delivery address, since staff admins are not rows in entitlements {@code users}.
     */
    private String emailOf(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsString("email");
    }

    /** Pull the staff user id off the JWT {@code sub} claim (set by the dev-login minter). */
    private UUID actorOf(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma == -1 ? forwarded.trim() : forwarded.substring(0, comma).trim();
        }
        return request.getRemoteAddr();
    }
}
