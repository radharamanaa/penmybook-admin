package dev.bookforge.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.downstream.DownstreamCallException;
import dev.bookforge.adminapi.downstream.DownstreamConflictException;
import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.DownstreamResult;
import dev.bookforge.adminapi.downstream.ForceResolveReversedCallResult;
import dev.bookforge.adminapi.downstream.HttpDownstreamClient;
import dev.bookforge.adminapi.downstream.MarkPaidByHandCallResult;
import dev.bookforge.adminapi.downstream.RefundRequestResolveResult;
import dev.bookforge.adminapi.downstream.ServiceAccountTokenFactory;
import dev.bookforge.adminapi.downstream.WalletUnfreezeCallResult;
import dev.bookforge.adminapi.dto.AccountStateView;
import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.WalletSummaryView;
import dev.bookforge.adminapi.dto.AdminActionResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.OrgView;
import dev.bookforge.adminapi.dto.OrgWalletStateView;
import dev.bookforge.adminapi.dto.PlatformAdminUpdateView;
import dev.bookforge.adminapi.dto.RefundRequestQueueEntry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.InMemoryStepUpTokenStore;
import dev.bookforge.adminapi.security.StepUpTokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Audited admin operations. Every write:
 * <ol>
 *   <li>validates the action-bound step-up OTP token — signature, action and expiry (reject
 *       {@code step_up_required} when absent, {@code step_up_invalid} when bound to a different
 *       action, expired, or forged);</li>
 *   <li>verifies the token's {@code paramsHash} matches the canonical hash of THIS command's
 *       params (binds the token to the exact action body the user authorized — defense-in-depth
 *       on top of the downstream's own check);</li>
 *   <li>for grant / top-up / resolve, atomically marks the challenge consumed here; for
 *       {@code refund} (payout and force-resolve) billing's {@code step_up_consumption} is the
 *       single-use ledger so a 502 cannot leave a leftover spendable at {@code /internal/**};</li>
 *   <li>delegates the state change to the downstream callee (entitlements {@code /internal/admin/*}
 *       or billing {@code /internal/refund}), which performs the change AND its audit row in one
 *       transaction;</li>
 *   <li>relays {@code success} + {@code auditEntryId}. If the downstream reports failure (e.g. the
 *       audit insert failed, rolling back the whole action), this returns {@code success=false}.</li>
 * </ol>
 * See specs/05-admin-api.md and contracts/internal-api.md.
 */
@Service
public class AdminService {

    private static final String GRANT_CREDIT = "grant_credit";
    private static final String SUSPEND = "suspend";
    private static final String UNSUSPEND = "unsuspend";
    private static final String FLAG_DEVELOPER = "flag_developer";
    private static final String REFUND = "refund";
    private static final String RESOLVE_REFUND_REQUEST = "resolve_refund_request";
    private static final String WALLET_TOP_UP = "wallet_top_up";
    private static final String WALLET_UNFREEZE = "wallet_unfreeze";
    private static final String REFUND_MARK_PAID = "refund_mark_paid";
    private static final String SET_PLATFORM_ADMIN = "set_platform_admin";

    /**
     * Actions whose step-up token billing verifies AND spends in its own {@code step_up_consumption}
     * ledger. admin-api checks them but does not claim them, so a 502 cannot leave an approval
     * that is spent here yet never used there (G1).
     */
    private static final java.util.Set<String> BILLING_SPENDS_TOKEN = java.util.Set.of(REFUND, WALLET_UNFREEZE, REFUND_MARK_PAID);

    private static final String USER_OWNER_TYPE = "USER";
    private static final String ORG_OWNER_TYPE = "ORG";

    private static final String REJECT_STEP_UP_REQUIRED = "step_up_required";
    private static final String REJECT_STEP_UP_INVALID = "step_up_invalid";
    /** The payout call timed out: the card refund may have happened. Never a plain failure. */
    public static final String REFUND_OUTCOME_UNKNOWN = "refund_outcome_unknown";
    /**
     * Billing found no refund under this operation's key and refused the approval. Whether that
     * means "spent by an earlier attempt that may still land" depends on whether this was a retry,
     * which only the caller knows.
     */
    public static final String REFUND_STEP_UP_REFUSED = "refund_step_up_refused";
    /** The payout request never reached billing: nothing moved and the approval is still unspent. */
    public static final String REFUND_NOT_SENT = "refund_not_sent";
    /** Billing answered 5xx: it may have reached Stripe before failing. */
    public static final String REFUND_BILLING_ERROR = "refund_billing_error";
    /** Billing answered 409 refund_outcome_unknown: it called Stripe and the outcome is not known. */
    public static final String REFUND_BILLING_CONFLICT = "refund_billing_conflict";
    /** Stripe failed or cancelled the refund; billing put the wallet back. Nothing was paid. */
    public static final String REFUND_DECLINED = "refund_declined";
    /** Billing's coded 502: Stripe answered the payout POST and refused it. Nothing was paid. */
    public static final String REFUND_STRIPE_REFUSED = "refund_stripe_refused";

    private final StepUpVerifier stepUpVerifier;
    private final AdminParamsHasher paramsHasher;
    private final StepUpTokenStore tokenStore;
    private final DownstreamClient downstream;

    /** Primary constructor — used by Spring (and tests that inject a mocked client). */
    @Autowired
    public AdminService(StepUpVerifier stepUpVerifier,
                        AdminParamsHasher paramsHasher,
                        StepUpTokenStore tokenStore,
                        DownstreamClient downstream) {
        this.stepUpVerifier = stepUpVerifier;
        this.paramsHasher = paramsHasher;
        this.tokenStore = tokenStore;
        this.downstream = downstream;
    }

    /**
     * No-arg constructor for the wired-POJO BDD path. Resolves downstream base URLs and the
     * service-account signing key from system properties / env (same keys application.yml binds),
     * so the suite can repoint admin-api at a WireMock mesh without a Spring context.
     */
    public AdminService() {
        this(new StepUpVerifier(defaultJwtProperties()),
                new AdminParamsHasher(),
                new InMemoryStepUpTokenStore(),
                defaultDownstreamClient());
    }

    private static ServiceJwtProperties defaultJwtProperties() {
        return new ServiceJwtProperties(
                resolve("SERVICE_JWT_SIGNING_KEY", "service-jwt.signing-key", "dev-only-hs256-shared-secret-change-me"),
                resolve("SERVICE_JWT_ISSUER", "service-jwt.issuer", "bookforge-internal"),
                resolve("SERVICE_JWT_AUDIENCE", "service-jwt.audience", "admin-api"));
    }

    private static DownstreamClient defaultDownstreamClient() {
        DownstreamProperties downstreamProps = new DownstreamProperties(
                resolve("ENTITLEMENTS_URL", "downstream.entitlements-url", "http://localhost:8101"),
                resolve("BILLING_URL", "downstream.billing-url", "http://localhost:8102"),
                resolve("ORG_URL", "downstream.org-url", "http://localhost:8103"),
                resolve("CURATOR_INTERNAL_URL", "downstream.curator-url", "http://localhost:8000"));
        ServiceJwtProperties jwtProps = defaultJwtProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        return new HttpDownstreamClient(downstreamProps,
                new ServiceAccountTokenFactory(jwtProps, objectMapper), objectMapper);
    }

    private static String resolve(String envKey, String sysPropKey, String fallback) {
        String fromSysProp = System.getProperty(sysPropKey);
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return fromSysProp;
        }
        String fromEnv = System.getenv(envKey);
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : fallback;
    }

    public AdminActionResult grantCredit(AdminCommand command) {
        return runWrite(GRANT_CREDIT, command, downstream::grantCredit);
    }

    public AdminActionResult suspend(AdminCommand command) {
        return runWrite(SUSPEND, command, downstream::suspend);
    }

    /**
     * Reactivate a suspended account (a chargeback hold or a content review). Entitlements only
     * lifts SUSPENDED / SUSPENDED_FOR_REVIEW; for any other status it answers success=false with
     * the current status, which comes back here as {@link UnsuspendOutcome#notSuspended}.
     */
    public UnsuspendOutcome unsuspend(AdminCommand command) {
        return unsuspend(command, false, false, null);
    }

    /**
     * Reactivate, then (when asked) reset the curator's content-strike count (L5). Entitlements may
     * refuse with a 409 code (wallet frozen by a payment hold; wallet unknown and not acknowledged),
     * which comes back as {@link UnsuspendOutcome#refused}. A reset that fails after a successful
     * reactivation is reported, never undone into a failed reactivation.
     */
    public UnsuspendOutcome unsuspend(AdminCommand command, boolean acknowledgeWalletUnknown,
                                      boolean resetContentStrikes, String note) {
        StepUpVerifier.Result gate = gate(UNSUSPEND, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return UnsuspendOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return UnsuspendOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        DownstreamResult result;
        try {
            result = callDownstream(
                    () -> downstream.unsuspend(command, acknowledgeWalletUnknown, resetContentStrikes),
                    () -> new DownstreamResult(false, null, null));
        } catch (DownstreamConflictException refused) {
            return UnsuspendOutcome.refused(refused.code(), refused.detail());
        }
        if (!result.success()) {
            return result.accountStatus() == null
                    ? UnsuspendOutcome.downstreamRejected()
                    : UnsuspendOutcome.notSuspended(result.accountStatus());
        }
        if (!resetContentStrikes) {
            return UnsuspendOutcome.reactivated(result.auditEntryId(), result.accountStatus(), null, null);
        }
        try {
            downstream.resetContentStrikes(command.targetUserId(), command.staffUserId(), note);
            return UnsuspendOutcome.reactivated(result.auditEntryId(), result.accountStatus(), true, null);
        } catch (DownstreamCallException | CallNotPermittedException resetFailed) {
            return UnsuspendOutcome.reactivated(result.auditEntryId(), result.accountStatus(), false,
                    "The account is active again, but the content strikes were not reset: "
                            + resetFailed.getMessage());
        }
    }

    /**
     * Lift a wallet freeze. The token is bound over {@code {ownerType, ownerId}} (the command's
     * params) and spent by billing, not here.
     */
    public WalletUnfreezeOutcome unfreezeWallet(AdminCommand command, String note) {
        StepUpVerifier.Result gate = gate(WALLET_UNFREEZE, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return WalletUnfreezeOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return WalletUnfreezeOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        WalletUnfreezeCallResult result = callDownstream(
                () -> downstream.unfreezeWallet(command, note),
                WalletUnfreezeCallResult::failed);
        return result.success()
                ? WalletUnfreezeOutcome.done(result.lifted())
                : WalletUnfreezeOutcome.downstreamRejected();
    }

    /** An org wallet's freeze and unrecovered money (billing). */
    public OrgWalletStateView orgWalletState(UUID orgId) {
        WalletSummaryView wallet = downstream.walletSummary(ORG_OWNER_TYPE, orgId);
        return new OrgWalletStateView(orgId, wallet.walletExists(), wallet.balanceCents(),
                wallet.frozen(), wallet.frozenReason(), wallet.unrecoveredCents());
    }

    /**
     * Account status (entitlements) plus the wallet's freeze and unrecovered money (billing). A
     * billing outage only marks the wallet part unknown ({@code walletDegraded}): reactivating needs
     * entitlements alone, so the account part must still reach staff.
     */
    public AccountStateView accountState(UUID userId) {
        AccountStatusView status = downstream.accountStatus(userId);
        WalletSummaryView wallet;
        try {
            wallet = downstream.walletSummary(USER_OWNER_TYPE, userId);
        } catch (DownstreamCallException | CallNotPermittedException billingDown) {
            return new AccountStateView(userId, status.accountStatus(), status.suspensionKind(),
                    false, 0L, false, null, 0L, true, status.paymentHoldKind(),
                    downstream.contentStrikes(userId));
        }
        return new AccountStateView(userId, status.accountStatus(), status.suspensionKind(),
                wallet.walletExists(),
                wallet.balanceCents(), wallet.frozen(), wallet.frozenReason(), wallet.unrecoveredCents(),
                false, status.paymentHoldKind(), downstream.contentStrikes(userId));
    }

    /**
     * Outcome of a reactivation. {@code rejectionReason} is the step-up code; {@code accountStatus}
     * without success means the account was not suspended (nothing changed); both null means the
     * downstream failed.
     */
    public record UnsuspendOutcome(boolean success, UUID auditEntryId, String accountStatus,
                                   String rejectionReason, String refusalCode, String refusalMessage,
                                   Boolean contentStrikesReset, String contentStrikesResetError) {

        static UnsuspendOutcome reactivated(UUID auditEntryId, String accountStatus,
                                            Boolean contentStrikesReset, String contentStrikesResetError) {
            return new UnsuspendOutcome(true, auditEntryId, accountStatus, null, null, null,
                    contentStrikesReset, contentStrikesResetError);
        }

        static UnsuspendOutcome notSuspended(String accountStatus) {
            return new UnsuspendOutcome(false, null, accountStatus, null, null, null, null, null);
        }

        static UnsuspendOutcome rejected(String reason) {
            return new UnsuspendOutcome(false, null, null, reason, null, null, null, null);
        }

        /** Entitlements refused the reactivation (409) with a code staff must act on. */
        static UnsuspendOutcome refused(String code, String message) {
            return new UnsuspendOutcome(false, null, null, null, code, message, null, null);
        }

        static UnsuspendOutcome downstreamRejected() {
            return new UnsuspendOutcome(false, null, null, null, null, null, null, null);
        }
    }

    /** Outcome of a wallet unfreeze; {@code lifted=false} on success means it was not frozen. */
    public record WalletUnfreezeOutcome(boolean success, boolean lifted, String rejectionReason) {

        static WalletUnfreezeOutcome done(boolean lifted) {
            return new WalletUnfreezeOutcome(true, lifted, null);
        }

        static WalletUnfreezeOutcome rejected(String reason) {
            return new WalletUnfreezeOutcome(false, false, reason);
        }

        static WalletUnfreezeOutcome downstreamRejected() {
            return new WalletUnfreezeOutcome(false, false, null);
        }
    }

    /**
     * O-5: grant or revoke platform admin. Step-up gated and claimed here (entitlements does not
     * spend the token); the hash binds the target user and the new value, so an approval to revoke
     * cannot be replayed to grant.
     */
    public PlatformAdminOutcome setPlatformAdmin(AdminCommand command, boolean isPlatformAdmin) {
        StepUpVerifier.Result gate = gate(SET_PLATFORM_ADMIN, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return new PlatformAdminOutcome(null, REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return new PlatformAdminOutcome(null, REJECT_STEP_UP_INVALID);
        }
        return new PlatformAdminOutcome(
                downstream.setIsPlatformAdmin(command.targetUserId(), isPlatformAdmin), null);
    }

    /** {@code updated} on success; otherwise the step-up {@code rejectionReason}. */
    public record PlatformAdminOutcome(PlatformAdminUpdateView updated, String rejectionReason) {
    }

    public AdminActionResult flagDeveloper(AdminCommand command) {
        return runWrite(FLAG_DEVELOPER, command, downstream::flagDeveloper);
    }

    /**
     * Approve or reject a user-submitted refund request. Step-up gated like every other money
     * action: the token's paramsHash is bound over {requestId, decision, amountCents}, so a token
     * authorised to return $20 cannot be replayed to return $500. {@code note} is sent to billing
     * but is not in the hash — it is user-facing copy staff may edit after OTP start.
     *
     * <p>The gate applies to a REJECT too. It moves no money, but it is a decision the user is
     * told about verbatim, and it closes their only open request — so it deserves the same
     * attribution as an approval.
     *
     * <p>Hand-rolled beside {@link #refund} rather than through {@link #runWrite}: billing's
     * outcome (status, refunded) must reach the audit row and the SPA, and
     * {@link AdminActionResult} is shared by four other writes that do not carry those fields.
     */
    public ResolveRefundOutcome resolveRefundRequest(AdminCommand command) {
        Map<String, Object> hashed = new LinkedHashMap<>(command.params());
        hashed.remove("note");
        StepUpVerifier.Result gate = gate(RESOLVE_REFUND_REQUEST, command, hashed);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return ResolveRefundOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return ResolveRefundOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        RefundRequestResolveResult result = callDownstream(
                () -> downstream.resolveRefundRequest(command),
                RefundRequestResolveResult::failed);
        if (!result.success()) {
            return ResolveRefundOutcome.downstreamRejected();
        }
        return ResolveRefundOutcome.success(result.status(), result.approvedAmountCents(),
                result.refundedCents());
    }

    /**
     * Outcome of a step-up-gated refund-request resolve. On success carries billing's figures so
     * the audit row records what actually moved, not the ask. A {@code null} rejectionReason
     * after failure means billing did not confirm — 502, never a retry.
     */
    public record ResolveRefundOutcome(
            boolean success,
            String status,
            Long approvedAmountCents,
            long refundedCents,
            String rejectionReason) {

        static ResolveRefundOutcome success(String status, Long approvedAmountCents,
                                            long refundedCents) {
            return new ResolveRefundOutcome(true, status, approvedAmountCents, refundedCents, null);
        }

        static ResolveRefundOutcome rejected(String reason) {
            return new ResolveRefundOutcome(false, null, null, 0L, reason);
        }

        static ResolveRefundOutcome downstreamRejected() {
            return new ResolveRefundOutcome(false, null, null, 0L, null);
        }
    }

    public RefundOutcome refund(AdminCommand command) {
        StepUpVerifier.Result gate = gate(REFUND, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return RefundOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return RefundOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        DownstreamResult result = callDownstream(
                () -> downstream.refund(command),
                DownstreamResult::refundNotSent);
        if (result.success()) {
            return RefundOutcome.success(result.auditEntryId(), result.requestedCents(),
                    result.walletDebitedCents(), result.idempotentReplay());
        }
        return refundFailureOf(result);
    }

    private static RefundOutcome refundFailureOf(DownstreamResult result) {
        return switch (result.refundFailure()) {
            case BILLING_ERROR -> RefundOutcome.rejected(REFUND_BILLING_ERROR);
            // Carries billing's coded reason (not its free-text detail) for the staff wording.
            case BILLING_CONFLICT -> RefundOutcome.unsettled(REFUND_BILLING_CONFLICT,
                    result.refusalDetail());
            case STRIPE_REFUSED -> RefundOutcome.rejected(REFUND_STRIPE_REFUSED);
            case STEP_UP_REFUSED -> RefundOutcome.rejected(REFUND_STEP_UP_REFUSED);
            case NOT_SENT -> RefundOutcome.rejected(REFUND_NOT_SENT);
            case DECLINED -> RefundOutcome.rejected(REFUND_DECLINED);
            case REFUSED -> RefundOutcome.downstreamRejected(result.refusalDetail(),
                    result.refusalStatus());
            // A failure nobody classified cannot prove the card was not refunded.
            case OUTCOME_UNKNOWN, NONE -> RefundOutcome.rejected(REFUND_OUTCOME_UNKNOWN);
        };
    }

    /**
     * Restore a {@code PENDING_CARD_REFUND} after Stripe reversed the refund. Same {@code refund}
     * step-up action as payout, but the paramsHash includes {@code forceResolve=true} so a
     * payout token cannot be reused. Webhooks never call this.
     */
    public ForceResolveOutcome forceResolveReversed(AdminCommand command) {
        StepUpVerifier.Result gate = gate(REFUND, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return ForceResolveOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return ForceResolveOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        ForceResolveReversedCallResult result = callDownstream(
                () -> downstream.forceResolveReversed(command),
                ForceResolveReversedCallResult::failed);
        if (!result.success()) {
            return ForceResolveOutcome.downstreamRejected();
        }
        return ForceResolveOutcome.success(result.paymentId(), result.restoredCents());
    }

    /**
     * Outcome of a step-up-gated refund. On success carries the billing audit-row id and the
     * requested + actually-debited figures (D / F1). On rejection carries the mesh-standard reason
     * ({@code step_up_required} / {@code step_up_invalid}); a {@code null} reason means the
     * downstream rolled the refund back.
     */
    public record RefundOutcome(
            boolean success,
            UUID auditEntryId,
            long requestedCents,
            long walletDebitedCents,
            boolean idempotentReplay,
            String rejectionReason,
            String refusalMessage,
            int refusalStatus) {

        /** Without billing's words or status (every outcome except a refusal or a conflict). */
        public RefundOutcome(boolean success, UUID auditEntryId, long requestedCents,
                             long walletDebitedCents, boolean idempotentReplay,
                             String rejectionReason) {
            this(success, auditEntryId, requestedCents, walletDebitedCents, idempotentReplay,
                    rejectionReason, null, 0);
        }

        /** An outcome-unknown kind that carries billing's own words. */
        static RefundOutcome unsettled(String reason, String billingMessage) {
            return new RefundOutcome(false, null, 0L, 0L, false, reason, billingMessage, 0);
        }

        static RefundOutcome success(UUID auditEntryId, long requestedCents,
                                     long walletDebitedCents, boolean idempotentReplay) {
            return new RefundOutcome(true, auditEntryId, requestedCents, walletDebitedCents,
                    idempotentReplay, null);
        }

        static RefundOutcome rejected(String reason) {
            return new RefundOutcome(false, null, 0L, 0L, false, reason);
        }

        /**
         * Billing refused before any money moved; {@code refusalMessage} is its own words, if any,
         * and {@code refusalStatus} its HTTP status.
         */
        static RefundOutcome downstreamRejected(String refusalMessage, int refusalStatus) {
            return new RefundOutcome(false, null, 0L, 0L, false, null, refusalMessage, refusalStatus);
        }
    }

    /**
     * Settle an APPROVED refund request staff paid out by hand in the Stripe Dashboard (an org
     * request has no payout path here). The token is bound over {@code {requestId,
     * providerRefundId}} with the request's owner as the target, and billing spends it. The note is
     * sent to billing but is not in the hash, like a resolve note.
     */
    public MarkPaidByHandOutcome markRefundPaidByHand(AdminCommand command, String note) {
        StepUpVerifier.Result gate = gate(REFUND_MARK_PAID, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return MarkPaidByHandOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return MarkPaidByHandOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        MarkPaidByHandCallResult result = callDownstream(
                () -> downstream.markRefundPaidByHand(command, note),
                MarkPaidByHandCallResult::failed);
        if (result.success()) {
            return new MarkPaidByHandOutcome(true, result.request(), null, 0, null, null);
        }
        return new MarkPaidByHandOutcome(false, null, null, result.refusedStatus(),
                result.refusedCode(), result.refusedMessage());
    }

    /**
     * Outcome of "mark paid by hand". {@code rejectionReason} is admin-api's own step-up verdict;
     * {@code refusedStatus} &gt; 0 is billing's refusal, whose message staff see verbatim; neither
     * means billing did not answer.
     */
    public record MarkPaidByHandOutcome(
            boolean success,
            RefundRequestQueueEntry request,
            String rejectionReason,
            int refusedStatus,
            String refusedCode,
            String refusedMessage) {

        static MarkPaidByHandOutcome rejected(String reason) {
            return new MarkPaidByHandOutcome(false, null, reason, 0, null, null);
        }
    }

    public record ForceResolveOutcome(
            boolean success,
            UUID paymentId,
            long restoredCents,
            String rejectionReason) {

        static ForceResolveOutcome success(UUID paymentId, long restoredCents) {
            return new ForceResolveOutcome(true, paymentId, restoredCents, null);
        }

        static ForceResolveOutcome rejected(String reason) {
            return new ForceResolveOutcome(false, null, 0L, reason);
        }

        static ForceResolveOutcome downstreamRejected() {
            return new ForceResolveOutcome(false, null, 0L, null);
        }
    }

    /**
     * Step-up-gated wallet top-up (H3) — credits a user's wallet with spendable money. A top-up
     * mints money exactly like {@link #grantCredit(AdminCommand)}, so it runs the SAME step-up gate
     * (token signature / action / expiry / params-hash binding over {@code amountCents} /
     * single-use) before delegating to billing {@code POST /internal/wallet/credit}. {@code
     * amountCents} + {@code reason} are passed explicitly (the downstream credit takes them as
     * scalar args, not an {@link AdminCommand}); {@code amountCents} is also bound into the token's
     * paramsHash via the command's params so a token authorised for one amount cannot be replayed
     * for another. Returns the resulting balance on success, or the mesh-standard rejection reason.
     */
    public WalletTopUpOutcome walletTopUp(AdminCommand command, long amountCents, String reason) {
        StepUpVerifier.Result gate = gate(WALLET_TOP_UP, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return WalletTopUpOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return WalletTopUpOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        try {
            long balanceCents = downstream.walletTopUp(command.targetUserId(), amountCents, reason,
                    command.stepUpToken()).balanceCents();
            return WalletTopUpOutcome.success(balanceCents);
        } catch (CallNotPermittedException e) {
            return WalletTopUpOutcome.rejected(null);
        }
    }

    /**
     * Outcome of a step-up-gated wallet top-up. On success carries the resulting wallet balance; on
     * rejection carries the mesh-standard reason ({@code step_up_required} / {@code
     * step_up_invalid}).
     */
    public record WalletTopUpOutcome(boolean success, long balanceCents, String rejectionReason) {

        static WalletTopUpOutcome success(long balanceCents) {
            return new WalletTopUpOutcome(true, balanceCents, null);
        }

        static WalletTopUpOutcome rejected(String reason) {
            return new WalletTopUpOutcome(false, 0L, reason);
        }
    }

    /** Read-only read-through used to assert the effect of a suspend/unsuspend. */
    public AccountStatusView accountStatus(UUID userId) {
        return downstream.accountStatus(userId);
    }

    /** Read-only org context (name, wallet, admin cap) for the admin portal's user/org view. */
    public OrgView org(UUID orgId) {
        return downstream.org(orgId);
    }

    private AdminActionResult runWrite(String action, AdminCommand command,
                                       Function<AdminCommand, DownstreamResult> downstreamCall) {
        StepUpVerifier.Result outcome = gate(action, command);
        if (outcome instanceof StepUpVerifier.Result.Missing) {
            return new AdminActionResult(false, null, REJECT_STEP_UP_REQUIRED);
        }
        if (!(outcome instanceof StepUpVerifier.Result.Valid)) {
            return new AdminActionResult(false, null, REJECT_STEP_UP_INVALID);
        }

        DownstreamResult result = callDownstream(
                () -> downstreamCall.apply(command),
                () -> new DownstreamResult(false, null, null));
        if (!result.success()) {
            // Downstream rolled back (e.g. audit insert failed in the same transaction).
            return new AdminActionResult(false, null, null);
        }
        return new AdminActionResult(true, result.auditEntryId(), null);
    }

    /**
     * The full step-up gate, shared by every admin write. Verifies the token's signature, bound
     * action and expiry, that its {@code paramsHash} matches the canonical hash of THIS command's
     * params (so the authorization cannot be replayed for a different body), and atomically claims
     * the challenge id single-use. Returns:
     * <ul>
     *   <li>{@link StepUpVerifier.Result.Missing} — no token presented ({@code step_up_required});</li>
     *   <li>{@link StepUpVerifier.Result.Invalid} — bad signature / wrong action / expired /
     *       params-hash mismatch / replay ({@code step_up_invalid});</li>
     *   <li>{@link StepUpVerifier.Result.Valid} — the caller may issue the downstream call.</li>
     * </ul>
     * No downstream call is issued until this returns {@code Valid}.
     */
    private StepUpVerifier.Result gate(String action, AdminCommand command) {
        return gate(action, command, command.params());
    }

    private StepUpVerifier.Result gate(String action, AdminCommand command,
                                       Map<String, Object> hashParams) {
        StepUpVerifier.Result outcome = stepUpVerifier.verify(command.stepUpToken(), action);
        if (!(outcome instanceof StepUpVerifier.Result.Valid valid)) {
            return outcome;
        }

        // Params-hash binding: the token's paramsHash must equal the canonical hash of THIS
        // command's params. Constant-time compare so a forgery cannot use response timing to
        // probe the expected hash byte-by-byte.
        String expectedParamsHash = paramsHasher.compute(
                action, command.staffUserId(), command.targetUserId(), hashParams);
        if (!constantTimeEquals(expectedParamsHash, valid.paramsHash())) {
            return new StepUpVerifier.Result.Invalid();
        }

        // Billing spends the same token in step_up_consumption (refund, force-resolve, wallet
        // unfreeze). Claiming here first left a leftover spendable at billing after a 502 (G1).
        // Grant / resolve / top-up / unsuspend still claim locally because those callees do not
        // spend the full token.
        if (!BILLING_SPENDS_TOKEN.contains(action)) {
            if (!tokenStore.markConsumedOnce(valid.challengeId(), valid.expiresAtEpoch())) {
                return new StepUpVerifier.Result.Invalid();
            }
        }
        return valid;
    }

    /**
     * An open money-path breaker must not escape as a 500 after the OTP is already spent.
     * Map it to the same downstream-rejected outcome the timeout path uses (502 + DENY audit).
     */
    private static <T> T callDownstream(Supplier<T> call, Supplier<T> onOpenBreaker) {
        try {
            return call.get();
        } catch (CallNotPermittedException e) {
            return onOpenBreaker.get();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
