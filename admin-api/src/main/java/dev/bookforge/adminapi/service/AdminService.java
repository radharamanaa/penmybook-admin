package dev.bookforge.adminapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.DownstreamResult;
import dev.bookforge.adminapi.downstream.HttpDownstreamClient;
import dev.bookforge.adminapi.downstream.ServiceAccountTokenFactory;
import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.AdminActionResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.OrgView;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.InMemoryStepUpTokenStore;
import dev.bookforge.adminapi.security.StepUpTokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Function;

/**
 * Audited admin operations. Every write:
 * <ol>
 *   <li>validates the action-bound step-up OTP token — signature, action and expiry (reject
 *       {@code step_up_required} when absent, {@code step_up_invalid} when bound to a different
 *       action, expired, or forged);</li>
 *   <li>verifies the token's {@code paramsHash} matches the canonical hash of THIS command's
 *       params (binds the token to the exact action body the user authorized — defense-in-depth
 *       on top of the downstream's own check);</li>
 *   <li>atomically marks the token's {@code challengeId} as consumed and rejects on replay
 *       within the token's TTL (closes the in-TTL replay window before issuing any downstream
 *       call);</li>
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
     * Step-up-gated refund (B3) — the SINGLE refund path. Runs the same {@code runWrite(REFUND)}
     * gate the other writes use (token signature / action / expiry / params-hash binding /
     * single-use), then delegates to billing {@code POST /internal/refund}. On success the outcome
     * carries the clamped ask + what was actually debited so the controller can return
     * "asked X, refunded Y" (D / F1).
     */
    public RefundOutcome refund(AdminCommand command) {
        StepUpVerifier.Result gate = gate(REFUND, command);
        if (gate instanceof StepUpVerifier.Result.Missing) {
            return RefundOutcome.rejected(REJECT_STEP_UP_REQUIRED);
        }
        if (!(gate instanceof StepUpVerifier.Result.Valid)) {
            return RefundOutcome.rejected(REJECT_STEP_UP_INVALID);
        }
        DownstreamResult result = downstream.refund(command);
        if (!result.success()) {
            return RefundOutcome.downstreamRejected();
        }
        return RefundOutcome.success(result.auditEntryId(), result.requestedCents(),
                result.walletDebitedCents(), result.idempotentReplay());
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
        long balanceCents = downstream.walletTopUp(command.targetUserId(), amountCents, reason)
                .balanceCents();
        return WalletTopUpOutcome.success(balanceCents);
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

        DownstreamResult result = downstreamCall.apply(command);
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
        StepUpVerifier.Result outcome = stepUpVerifier.verify(command.stepUpToken(), action);
        if (!(outcome instanceof StepUpVerifier.Result.Valid valid)) {
            return outcome;
        }

        // Params-hash binding: the token's paramsHash must equal the canonical hash of THIS
        // command's params. Constant-time compare so a forgery cannot use response timing to
        // probe the expected hash byte-by-byte.
        String expectedParamsHash = paramsHasher.compute(
                action, command.staffUserId(), command.targetUserId(), command.params());
        if (!constantTimeEquals(expectedParamsHash, valid.paramsHash())) {
            return new StepUpVerifier.Result.Invalid();
        }

        // Single-use: claim the challenge id atomically. A second presentation of the same token
        // within its TTL loses the race and is rejected as a replay — BEFORE any downstream call
        // fires.
        if (!tokenStore.markConsumedOnce(valid.challengeId(), valid.expiresAtEpoch())) {
            return new StepUpVerifier.Result.Invalid();
        }
        return valid;
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
