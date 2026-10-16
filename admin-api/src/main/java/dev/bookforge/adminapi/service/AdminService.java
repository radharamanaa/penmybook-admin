package dev.bookforge.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.DownstreamResult;
import dev.bookforge.adminapi.downstream.ForceResolveReversedCallResult;
import dev.bookforge.adminapi.downstream.HttpDownstreamClient;
import dev.bookforge.adminapi.downstream.RefundRequestResolveResult;
import dev.bookforge.adminapi.downstream.RefundResidualSettleResult;
import dev.bookforge.adminapi.downstream.ServiceAccountTokenFactory;
import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.AdminActionResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.OrgView;
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
    private static final String SETTLE_REFUND_RESIDUAL = "settle_refund_residual";
    private static final String WALLET_TOP_UP = "wallet_top_up";

    private static final String REJECT_STEP_UP_REQUIRED = "step_up_required";
    private static final String REJECT_STEP_UP_INVALID = "step_up_invalid";

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
                resolve("ORG_URL", "downstream.org-url", "http://localhost:8103"));
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

    public AdminActionResult unsuspend(AdminCommand command) {
        return runWrite(UNSUSPEND, command, downstream::unsuspend);
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
     * outcome (status, refunded, residual) must reach the audit row and the SPA, and
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
                result.refundedCents(), result.residualCents());
    }

    public SettleResidualOutcome settleRefundResidual(AdminCommand command) {
        StepUpVerifier.Result gate = gate(SETTLE_REFUND_RESIDUAL, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return SettleResidualOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return SettleResidualOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        RefundResidualSettleResult result = callDownstream(
                () -> downstream.settleRefundResidual(command),
                RefundResidualSettleResult::failed);
        if (!result.success()) {
            return SettleResidualOutcome.downstreamRejected();
        }
        return SettleResidualOutcome.ok();
    }

    public record SettleResidualOutcome(boolean success, String rejectionReason) {
        static SettleResidualOutcome ok() {
            return new SettleResidualOutcome(true, null);
        }
        static SettleResidualOutcome rejected(String reason) {
            return new SettleResidualOutcome(false, reason);
        }
        static SettleResidualOutcome downstreamRejected() {
            return new SettleResidualOutcome(false, null);
        }
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
            long residualCents,
            String rejectionReason) {

        static ResolveRefundOutcome success(String status, Long approvedAmountCents,
                                            long refundedCents, long residualCents) {
            return new ResolveRefundOutcome(true, status, approvedAmountCents, refundedCents,
                    residualCents, null);
        }

        static ResolveRefundOutcome rejected(String reason) {
            return new ResolveRefundOutcome(false, null, null, 0L, 0L, reason);
        }

        static ResolveRefundOutcome downstreamRejected() {
            return new ResolveRefundOutcome(false, null, null, 0L, 0L, null);
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
                () -> new DownstreamResult(false, null, null));
        if (!result.success()) {
            return RefundOutcome.downstreamRejected();
        }
        return RefundOutcome.success(result.auditEntryId(), result.requestedCents(),
                result.walletDebitedCents(), result.idempotentReplay());
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
            String rejectionReason) {

        static RefundOutcome success(UUID auditEntryId, long requestedCents,
                                     long walletDebitedCents, boolean idempotentReplay) {
            return new RefundOutcome(true, auditEntryId, requestedCents, walletDebitedCents,
                    idempotentReplay, null);
        }

        static RefundOutcome rejected(String reason) {
            return new RefundOutcome(false, null, 0L, 0L, false, reason);
        }

        static RefundOutcome downstreamRejected() {
            return new RefundOutcome(false, null, 0L, 0L, false, null);
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
            long balanceCents = downstream.walletTopUp(command.targetUserId(), amountCents, reason)
                    .balanceCents();
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

        // Billing spends the same token in step_up_consumption (refund + force-resolve). Claiming
        // here first left a leftover spendable at billing after a 502 (G1). Grant / resolve /
        // top-up still claim locally because those callees do not spend the full token.
        if (!REFUND.equals(action)) {
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
