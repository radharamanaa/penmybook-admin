package dev.bookforge.adminapi.service;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.OtpChallengeResult;
import dev.bookforge.adminapi.downstream.OtpCodeVerifyResult;
import dev.bookforge.adminapi.dto.AdminOtpStartResponse;
import dev.bookforge.adminapi.dto.AdminOtpVerifyResponse;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.StepUpTokenIssuer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Admin step-up OTP orchestration (F2). admin-api does NOT own an OTP code store — it reuses the
 * entitlements one (so the dev plaintext code lands in {@code otp_challenges.dev_plaintext_code},
 * locally retrievable; AWS adds SES delivery later). Two steps:
 *
 * <ol>
 *   <li><b>start</b> — delegate to entitlements {@code POST /internal/otp/start} to mint a code for
 *       the signed-in staff admin and return a {@code challengeId}.</li>
 *   <li><b>verify</b> — delegate to entitlements {@code POST /internal/otp/verify} to CHECK the
 *       code, then on success mint admin-api's OWN action-bound step-up token (via {@link
 *       StepUpTokenIssuer}) whose {@code paramsHash} is bound over {@link AdminParamsHasher}'s
 *       canonical hash of (action, staffUserId, targetUserId, params). The SPA sends that token as
 *       {@code X-Step-Up-Token} on the gated write, where {@link AdminService} re-binds it.</li>
 * </ol>
 *
 * <p>The entitlements OTP challenge binds the code to the SAME action+params at start so the code
 * authorises the exact amount; admin-api's minted token then re-binds with its own canonical hash
 * the gated write recomputes. The two hashing schemes are independent on purpose — entitlements
 * proves the code, admin-api proves the params at the write boundary.
 */
@Service
public class AdminOtpService {

    /** admin step-up action → entitlements {@code OtpAction} enum name. */
    private static final String ACTION_GRANT_CREDIT = "grant_credit";
    private static final String ACTION_REFUND = "refund";
    private static final String ACTION_RESOLVE_REFUND_REQUEST = "resolve_refund_request";
    private static final String ACTION_WALLET_TOP_UP = "wallet_top_up";
    private static final String ACTION_UNSUSPEND = "unsuspend";
    private static final String ACTION_WALLET_UNFREEZE = "wallet_unfreeze";
    private static final String ACTION_REFUND_MARK_PAID = "refund_mark_paid";
    private static final String ACTION_SET_PLATFORM_ADMIN = "set_platform_admin";
    private static final String OTP_ACTION_ADMIN_WRITE = "ADMIN_WRITE";
    private static final String OTP_ACTION_REFUND = "REFUND";
    private static final String OTP_ACTION_RESOLVE_REFUND_REQUEST = "RESOLVE_REFUND_REQUEST";

    private final DownstreamClient downstream;
    private final AdminParamsHasher paramsHasher;
    private final StepUpTokenIssuer tokenIssuer;

    public AdminOtpService(DownstreamClient downstream, AdminParamsHasher paramsHasher,
                           StepUpTokenIssuer tokenIssuer) {
        this.downstream = downstream;
        this.paramsHasher = paramsHasher;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * Start an OTP challenge for the staff admin against the entitlements OTP store.
     * {@code staffEmail} is the delivery address (D17): staff admins live in book_admin's
     * store, not entitlements {@code users}, so entitlements cannot look the email up itself.
     */
    public AdminOtpStartResponse start(UUID staffUserId, String staffEmail, String action,
                                       UUID targetUserId, Map<String, Object> params) {
        Map<String, Object> challengeParams = challengeParams(action, staffUserId, targetUserId, params);
        OtpChallengeResult result = downstream.otpStart(
                staffUserId, staffEmail, otpActionFor(action), challengeParams);
        return new AdminOtpStartResponse(result.challengeId(), result.expiresAt(), result.deliveryHint());
    }

    /**
     * Verify the submitted code at entitlements; on success mint admin-api's action-bound step-up
     * token. A failed code yields {@code verified=false} with the entitlements failure reason and
     * NO token.
     */
    public AdminOtpVerifyResponse verify(UUID staffUserId, UUID challengeId, String code,
                                         String action, UUID targetUserId, Map<String, Object> params) {
        Map<String, Object> challengeParams = challengeParams(action, staffUserId, targetUserId, params);
        OtpCodeVerifyResult result = downstream.otpVerify(challengeId, code,
                otpActionFor(action), challengeParams);
        if (!result.verified()) {
            return new AdminOtpVerifyResponse(false, null, null, result.failureReason());
        }
        String paramsHash = paramsHasher.compute(action, staffUserId, targetUserId, params);
        StepUpTokenIssuer.Issued issued = tokenIssuer.issue(challengeId.toString(), action, paramsHash);
        return new AdminOtpVerifyResponse(true, issued.token(), issued.expiresAt(), null);
    }

    /**
     * Params bound into the entitlements OTP challenge — the SAME map at start and verify so the
     * code's params-hash check passes. We include the staff + target identity alongside the action
     * params so the OTP challenge itself is identity-scoped (an admin's code cannot be used to
     * authorise a different staff/target pair).
     */
    private Map<String, Object> challengeParams(String action, UUID staffUserId, UUID targetUserId,
                                                Map<String, Object> params) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("action", action);
        out.put("staffUserId", staffUserId == null ? null : staffUserId.toString());
        out.put("targetUserId", targetUserId == null ? null : targetUserId.toString());
        if (params != null) {
            out.putAll(params);
        }
        return out;
    }

    private String otpActionFor(String action) {
        return switch (action) {
            // A wallet top-up mints spendable money exactly like a goodwill grant, so it rides the
            // same generic ADMIN_WRITE OTP action (no new entitlements OtpAction enum value needed).
            // Lifting a hold (account reactivate, wallet unfreeze) is a staff write too: same
            // ADMIN_WRITE OTP, so entitlements' otp_challenges CHECK needs no new value.
            // Granting or revoking platform admin (O-5) is a staff write on the same footing.
            case ACTION_GRANT_CREDIT, ACTION_WALLET_TOP_UP, ACTION_UNSUSPEND, ACTION_WALLET_UNFREEZE,
                 ACTION_SET_PLATFORM_ADMIN ->
                    OTP_ACTION_ADMIN_WRITE;
            case ACTION_REFUND -> OTP_ACTION_REFUND;
            // Marking a request paid by hand settles it like a resolve does: same OTP action, so
            // entitlements' otp_challenges CHECK needs no new value.
            case ACTION_RESOLVE_REFUND_REQUEST, ACTION_REFUND_MARK_PAID -> OTP_ACTION_RESOLVE_REFUND_REQUEST;
            default -> throw new IllegalArgumentException("unsupported admin step-up action: " + action);
        };
    }
}
