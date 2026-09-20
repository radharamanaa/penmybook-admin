package dev.bookforge.adminapi.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Stands in for the downstream mesh (entitlements / billing / org) for the BDD suite, returning
 * the exact frozen JSON from contracts/internal-api.md. Started once before all scenarios; the
 * downstream base URLs are pushed into system properties so {@code new AdminService()} (a wired
 * POJO in {@link AdminSteps}) repoints at this mock instead of a live service.
 *
 * <p>The "audit-insert failure rolls back" scenario is modelled by a higher-priority stub: a
 * grant_credit whose {@code reason} equals {@link #AUDIT_FAULT_REASON} returns
 * {@code success:false} (the callee's same-tx audit insert failed → the whole action rolled back).
 */
public class AdminWireMock {

    /** Marker reason that triggers the WireMock audit-failure stub. */
    public static final String AUDIT_FAULT_REASON = "__fault_audit__";

    private static WireMockServer server;

    /** Exposed so that test steps can call {@code .verify()/.stubFor()/.resetAll()} on the same instance. */
    public static WireMockServer server() {
        return server;
    }

    @BeforeAll
    public static void startMesh() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        String baseUrl = "http://localhost:" + server.port();
        // Repoint admin-api's downstream clients (resolved by AdminService's no-arg constructor).
        System.setProperty("downstream.entitlements-url", baseUrl);
        System.setProperty("downstream.billing-url", baseUrl);
        System.setProperty("downstream.org-url", baseUrl);

        resetToCanonicalStubs();
    }

    /**
     * Wipe all registered stubs and re-register the canonical happy-path mesh contract. Called from
     * the per-scenario hook so a stub override registered by one scenario (e.g. "endpoint returns
     * HTTP 500") can't leak into the next.
     */
    public static void resetToCanonicalStubs() {
        if (server == null) {
            return;
        }
        server.resetAll();
        stubEntitlementsAdmin();
        stubBillingRefund();
        stubBillingForceResolveReversed();
        stubAccountStatus();
        stubOrgView();
        // Phase 3b — admin-portal HTTP surface ↓
        stubBillingWalletCredit();
        stubBillingPaymentsHealth();
        stubBillingRefundResiduals();
        stubEntitlementsSetPlatformAdmin();
        stubEntitlementsUserSearch();
        stubEntitlementsUsersByIds();
        stubEntitlementsWaitlist();
        stubEntitlementsPreAdmit();
        stubEntitlementsOrgPricingUpsert();
        stubEntitlementsAuditLog();
        // Admin step-up OTP (F2) — entitlements code store ↓
        stubEntitlementsOtpStart();
        stubEntitlementsOtpVerify();
    }

    @AfterAll
    public static void stopMesh() {
        if (server != null) {
            server.stop();
        }
        System.clearProperty("downstream.entitlements-url");
        System.clearProperty("downstream.billing-url");
        System.clearProperty("downstream.org-url");
    }

    private static void stubEntitlementsAdmin() {
        // Audit-failure path: grant_credit with the fault reason rolls back (success:false).
        server.stubFor(post(urlMatching("/internal/admin/grant_credit"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.reason", containing(AUDIT_FAULT_REASON)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"auditEntryId\":null}")));

        // Happy path: grant_credit / suspend / unsuspend / flag_developer all succeed + audit.
        server.stubFor(post(urlMatching("/internal/admin/grant_credit"))
                .atPriority(5)
                .willReturn(adminSuccess(null)));
        server.stubFor(post(urlMatching("/internal/admin/suspend"))
                .willReturn(adminSuccess("SUSPENDED")));
        server.stubFor(post(urlMatching("/internal/admin/unsuspend"))
                .willReturn(adminSuccess("ACTIVE")));
        server.stubFor(post(urlMatching("/internal/admin/flag_developer"))
                .willReturn(adminSuccess(null)));
    }

    private static void stubBillingRefund() {
        // billing POST /internal/refund (D6 / P11 / F1) — the SINGLE refund path. Returns the
        // clamped ask (requestedCents) and what was actually debited (walletDebitedCents) so the
        // admin sees "asked X, refunded Y". idempotentReplay=false on a fresh refund.
        server.stubFor(post(urlMatching("/internal/refund"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"requestedCents\":2500,\"walletDebitedCents\":2500,"
                                + "\"auditRecorded\":true,\"idempotentReplay\":false,"
                                + "\"auditEntryId\":\"" + UUID.randomUUID() + "\"}")));
    }

    private static void stubBillingForceResolveReversed() {
        server.stubFor(post(urlPathMatching(
                "/internal/admin/payments/[a-fA-F0-9-]{36}/force-resolve-reversed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"" + UUID.randomUUID()
                                + "\",\"restoredCents\":2500}")));
    }

    private static void stubAccountStatus() {
        // Read-through: after a suspend, the target's status reads back as SUSPENDED.
        server.stubFor(get(urlPathMatching("/internal/admin/users/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountStatus\":\"SUSPENDED\"}")));
    }

    private static void stubOrgView() {
        server.stubFor(get(urlPathMatching("/internal/orgs/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orgId\":\"" + UUID.randomUUID() + "\",\"name\":\"Acme Publishing\","
                                + "\"walletId\":\"" + UUID.randomUUID() + "\",\"maxAdmins\":2,"
                                + "\"domainLockDomain\":null,\"createdBy\":\"" + UUID.randomUUID() + "\"}")));
    }

    // ───────── Phase 3b admin-portal stubs ─────────

    /** billing {@code POST /internal/wallet/credit} — admin top-up. Returns 200 + new balance. */
    private static void stubBillingWalletCredit() {
        server.stubFor(post(urlMatching("/internal/wallet/credit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"balanceCents\":1500}")));
    }

    /**
     * billing residual queue. Empty array is a clear queue, not degraded. Settle HTTP 200 is
     * success even when billing would report {@code settled=false} (already-resolved replay).
     */
    private static void stubBillingRefundResiduals() {
        server.stubFor(get(urlPathEqualTo("/internal/refund-residuals"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        server.stubFor(post(urlPathEqualTo("/internal/refund-residuals/settle"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"settled\":true,\"residualId\":\"" + UUID.randomUUID() + "\"}")));
    }

    /** billing {@code GET /internal/admin/payments/health} — payment-integration health relay. */
    private static void stubBillingPaymentsHealth() {
        server.stubFor(get(urlPathEqualTo("/internal/admin/payments/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"stuckRefundCount\":1,\"stuckTopupCount\":2,\"abandonedTopupCount\":3,"
                                + "\"lastCompletedWebhookAt\":\"2026-07-20T12:00:00Z\","
                                + "\"completedWebhooks24h\":4,"
                                + "\"stuckRefundThresholdMinutes\":60,\"stuckTopupThresholdMinutes\":15}")));
    }

    /** entitlements {@code PATCH /internal/admin/users/{userId}} — set is_platform_admin. */
    private static void stubEntitlementsSetPlatformAdmin() {
        server.stubFor(patch(urlPathMatching("/internal/admin/users/[a-fA-F0-9-]{36}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"isPlatformAdmin\":true}")));
    }

    /** entitlements {@code GET /internal/admin/users/search?email=&limit=} — fuzzy search. */
    private static void stubEntitlementsUserSearch() {
        // A WAITLISTED tester — the accountStatus is what the operator approves on (D11).
        server.stubFor(get(urlPathEqualTo("/internal/admin/users/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"userId\":\"" + UUID.randomUUID() + "\","
                                + "\"email\":\"target@example.com\",\"accountStatus\":\"WAITLISTED\","
                                + "\"isDeveloper\":false,\"createdAt\":\"2026-05-01T00:00:00Z\"}]")));
    }

    /**
     * entitlements {@code POST /internal/admin/users/by-ids} — empty success so a queue read
     * without names is identity-fine, not identity-degraded. Scenarios that need names override.
     */
    private static void stubEntitlementsUsersByIds() {
        server.stubFor(post(urlPathEqualTo("/internal/admin/users/by-ids"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
    }

    /** entitlements waitlist endpoints: admit (admitted/reason/accountStatus) + config get/set (D11). */
    private static void stubEntitlementsWaitlist() {
        server.stubFor(post(urlMatching("/internal/admin/waitlist-admit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"admitted\":true,\"accountStatus\":\"ACTIVE\",\"reason\":null}")));
        server.stubFor(get(urlPathEqualTo("/internal/admin/waitlist/config"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"enabled\":true,\"weeklyApprovalLimit\":50,\"admittedThisWeek\":3}")));
        server.stubFor(post(urlPathEqualTo("/internal/admin/waitlist/config"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"enabled\":true,\"weeklyApprovalLimit\":100,\"admittedThisWeek\":3}")));
    }

    /** entitlements pre-admit endpoints (2026-09-03): accept by default; the list has one pending row. */
    private static void stubEntitlementsPreAdmit() {
        server.stubFor(post(urlPathEqualTo("/internal/admin/pre-admit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"admitted\":true,\"reason\":null,\"expiresAt\":\"2026-10-03T10:00:00Z\"}")));
        server.stubFor(get(urlPathEqualTo("/internal/admin/pre-admits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"019200aa-0000-7000-8000-000000000001\","
                                + "\"email\":\"pending@example.com\","
                                + "\"admittedBy\":\"019200aa-0000-7000-8000-0000000000aa\","
                                + "\"createdAt\":\"2026-09-03T10:00:00Z\","
                                + "\"expiresAt\":\"2026-10-03T10:00:00Z\"}]")));
    }

    /** entitlements {@code POST /internal/pricing/org-override} — upsert per-org price. */
    private static void stubEntitlementsOrgPricingUpsert() {
        server.stubFor(post(urlMatching("/internal/pricing/org-override"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"action\":\"CHAPTER_GENERATE\",\"valueCents\":100,"
                                + "\"previousValueCents\":null,\"updatedAt\":\"2026-06-01T00:00:00Z\"}")));
    }

    /**
     * entitlements {@code POST /internal/audit-log} — admin-api ships every ALLOW / DENY row
     * here. Returns 204 (we don't care about a body); the request journal is what the assertions
     * inspect.
     */
    private static void stubEntitlementsAuditLog() {
        server.stubFor(post(urlMatching("/internal/audit-log"))
                .willReturn(aResponse().withStatus(204)));
    }

    /**
     * Challenge id the OTP-start stub returns, so verify scenarios can echo it back. Regenerated on
     * every {@link #resetToCanonicalStubs()} (i.e. per scenario) so it is UNIQUE per challenge —
     * mirroring real entitlements, which never reuses a challenge id. A fixed id would collide in
     * admin-api's single-use step-up ledger (a Spring singleton across scenarios): the grant
     * scenario would consume it and the later refund scenario, minting a token over the SAME id,
     * would lose the single-use race and be (wrongly) rejected as a {@code step_up_invalid} replay.
     */
    public static UUID otpChallengeId = UUID.randomUUID();
    /** The one OTP code the verify stub accepts; any other code returns verified:false. */
    public static final String OTP_GOOD_CODE = "123456";

    /** entitlements {@code POST /internal/otp/start} — mint a code, return a per-scenario challengeId. */
    private static void stubEntitlementsOtpStart() {
        otpChallengeId = UUID.randomUUID();
        server.stubFor(post(urlMatching("/internal/otp/start"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"challengeId\":\"" + otpChallengeId + "\","
                                + "\"expiresAt\":\"2099-01-01T00:00:00Z\",\"deliveryChannel\":\"EMAIL\","
                                + "\"deliveryTargetHint\":\"your registered email\"}")));
    }

    /**
     * entitlements {@code POST /internal/otp/verify} — accepts {@link #OTP_GOOD_CODE} (verified:true)
     * and rejects every other code (verified:false, failureReason=wrong_code).
     */
    private static void stubEntitlementsOtpVerify() {
        server.stubFor(post(urlMatching("/internal/otp/verify"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.code",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(OTP_GOOD_CODE)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"verified\":true,\"stepUpToken\":null,"
                                + "\"expiresAt\":null,\"failureReason\":null}")));
        server.stubFor(post(urlMatching("/internal/otp/verify"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"verified\":false,\"stepUpToken\":null,"
                                + "\"expiresAt\":null,\"failureReason\":\"wrong_code\"}")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder adminSuccess(String accountStatus) {
        String statusFragment = accountStatus == null ? "" : ",\"accountStatus\":\"" + accountStatus + "\"";
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"auditEntryId\":\"" + UUID.randomUUID() + "\"" + statusFragment + "}");
    }
}
