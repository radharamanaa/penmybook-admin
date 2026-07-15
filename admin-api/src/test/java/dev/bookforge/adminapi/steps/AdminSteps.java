package dev.bookforge.adminapi.steps;

import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.AdminActionResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.service.AdminService;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * All admin-api BDD steps in one cohesive class. RED PHASE: Given steps record POJO state only;
 * When steps call the AdminService stub which throws "not implemented", so every scenario
 * fails at its first behavioural step.
 */
public class AdminSteps {

    private final AdminService adminService = new AdminService();
    private final AdminParamsHasher paramsHasher = new AdminParamsHasher();

    private final UUID staffId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private String tokenAction = "grant_credit";
    private boolean auditWillFail = false;

    private AdminActionResult lastResult;
    private AccountStatusView lastStatus;
    private String lastObservedToken;
    private AdminActionResult firstReplayResult;
    private AdminActionResult secondReplayResult;

    private AdminCommand cmd(String action, String stepUpToken, Map<String, Object> params) {
        return new AdminCommand(staffId, targetId, action, stepUpToken, params);
    }

    /**
     * Mints a step-up token that ALSO passes the admin-api params-hash binding check by computing
     * the same canonical hash {@link AdminParamsHasher} uses in production over the SAME staff/
     * target/params triple {@link AdminService} will hash on receipt. Use this for happy-path
     * scenarios; forgery scenarios keep using the one-arg minter on purpose.
     */
    private String boundToken(String action, Map<String, Object> params) {
        String paramsHash = paramsHasher.compute(action, staffId, targetId, params);
        return StepUpTokenMinter.validFor(action, paramsHash);
    }

    // ───────────── Hooks: reset WireMock between scenarios ─────────────

    @Before
    public void resetWireMockState() {
        // Wipe all per-scenario stub overrides (re-registers the canonical happy-path stubs).
        AdminWireMock.resetToCanonicalStubs();
    }

    @After
    public void clearRequestLog() {
        // Clear request journal so verify() assertions in one scenario can't see the prior scenario's calls.
        AdminWireMock.server().resetRequests();
    }

    // ───────────── Given (state only) ─────────────

    @Given("a step-up token issued for action {string}")
    public void tokenForAction(String action) { this.tokenAction = action; }

    @Given("audit insertion will fail")
    public void auditWillFail() { this.auditWillFail = true; }

    // ───────────── When (call stubs → throw → red) ─────────────

    @When("the admin grants credit without a step-up token")
    public void grantsWithoutStepUp() {
        lastResult = adminService.grantCredit(cmd("grant_credit", null, Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with a valid step-up token")
    public void grantsWithStepUp() {
        Map<String, Object> params = Map.of("amountCents", 500L);
        lastResult = adminService.grantCredit(
                cmd("grant_credit", boundToken("grant_credit", params), params));
    }

    @When("the admin grants {long} cents with a valid step-up token")
    public void grantsNCents(long amountCents) {
        // When the audit-fault flag is set, tag the reason so the WireMock downstream returns
        // success=false (audit insert failed in the same tx → whole action rolls back).
        String reason = auditWillFail ? AdminWireMock.AUDIT_FAULT_REASON : "goodwill";
        Map<String, Object> params = Map.of("amountCents", amountCents, "reason", reason);
        lastResult = adminService.grantCredit(cmd("grant_credit",
                boundToken("grant_credit", params), params));
    }

    @When("the admin uses it to grant credit")
    public void usesTokenToGrant() {
        // Token was minted (validly signed) for tokenAction (e.g. "refund") but presented for
        // grant_credit — the verifier rejects on action mismatch despite the good signature.
        lastResult = adminService.grantCredit(
                cmd("grant_credit", StepUpTokenMinter.validFor(tokenAction), Map.of("amountCents", 500L)));
    }

    @When("the admin suspends a user with a valid step-up token")
    public void suspends() {
        Map<String, Object> params = Map.of();
        lastResult = adminService.suspend(cmd("suspend", boundToken("suspend", params), params));
    }

    @When("the admin refunds a payment with a valid step-up token")
    public void refunds() {
        Map<String, Object> params = Map.of(
                "paymentId", UUID.randomUUID().toString(),
                "refundAmountCents", 2500L);
        AdminService.RefundOutcome outcome =
                adminService.refund(cmd("refund", boundToken("refund", params), params));
        // The refund path returns a richer RefundOutcome (requested + debited figures); collapse it
        // to the AdminActionResult shape the shared success / audit-entry Then steps assert on.
        lastResult = new AdminActionResult(outcome.success(), outcome.auditEntryId(),
                outcome.rejectionReason());
    }

    // ───────────── Group A — HMAC forgery scenarios ─────────────

    @When("the admin grants credit with a step-up token signed by the wrong key")
    public void grantsWithWrongKeyToken() {
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.mintedWithWrongKey("grant_credit"), Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with a step-up token whose signature was tampered")
    public void grantsWithTamperedSignatureToken() {
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.tamperedSignatureFor("grant_credit"), Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with a step-up token whose action segment was rewritten from refund")
    public void grantsWithActionRewrittenToken() {
        // Signed for "refund", then action field rewritten to "grant_credit" without re-signing.
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.actionRewrittenFrom("refund", "grant_credit"), Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with an expired step-up token")
    public void grantsWithExpiredToken() {
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.expiredFor("grant_credit"), Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with a malformed step-up token")
    public void grantsWithMalformedToken() {
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.malformed(), Map.of("amountCents", 500L)));
    }

    @When("the admin grants credit with a step-up token whose action segment is empty")
    public void grantsWithEmptyActionToken() {
        lastResult = adminService.grantCredit(cmd("grant_credit",
                StepUpTokenMinter.emptyActionSegment(), Map.of("amountCents", 500L)));
    }

    // ───────────── Group B — Audit-real assertions ─────────────

    @Given("the entitlements admin endpoint is reset")
    public void resetEntitlementsCallLog() {
        AdminWireMock.server().resetRequests();
    }

    @When("the admin grants {long} cents with a valid step-up token for staff observation")
    public void grantsForStaffObservation(long amountCents) {
        // Same as grantsNCents but we explicitly capture the staffId/token for the verify step.
        Map<String, Object> params = Map.of("amountCents", amountCents, "reason", "goodwill");
        lastObservedToken = boundToken("grant_credit", params);
        lastResult = adminService.grantCredit(cmd("grant_credit", lastObservedToken, params));
    }

    @Then("the entitlements admin endpoint was called with the staff identity, amountCents, reason and step-up token")
    public void verifyEntitlementsAdminWasCalled() {
        AdminWireMock.server().verify(postRequestedFor(urlMatching("/internal/admin/grant_credit"))
                .withRequestBody(matchingJsonPath("$.staffUserId", equalTo(staffId.toString())))
                .withRequestBody(matchingJsonPath("$.targetUserId", equalTo(targetId.toString())))
                .withRequestBody(matchingJsonPath("$.ownerType", equalTo("USER")))
                .withRequestBody(matchingJsonPath("$.ownerId", equalTo(targetId.toString())))
                .withRequestBody(matchingJsonPath("$.amountCents", equalTo("500")))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("goodwill")))
                // B4 fix: the downstream stepUpChallengeId is the OTP CHALLENGE UUID (the token's
                // first segment), NOT the full pipe-delimited step-up token string.
                .withRequestBody(matchingJsonPath("$.stepUpChallengeId",
                        equalTo(lastObservedToken.split("\\|", 2)[0]))));
    }

    @Given("the entitlements admin endpoint will return HTTP {int}")
    public void entitlementsReturnsHttpStatus(int httpStatus) {
        // Highest-priority stub: any grant_credit responds with this status code.
        AdminWireMock.server().stubFor(post(urlMatching("/internal/admin/grant_credit"))
                .atPriority(0)
                .willReturn(aResponse()
                        .withStatus(httpStatus)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"downstream_fault\"}")));
    }

    @Then("the action is reported as failed and not as a success")
    public void actionIsReportedAsFailed() {
        // A 4xx/5xx from the downstream MUST NOT map to success=true.
        assertThat(lastResult.success())
                .as("Downstream non-2xx response must not be surfaced as success=true")
                .isFalse();
    }

    // ───────────── Group C — Single-use replay rejection ─────────────

    @When("the admin grants credit with the same step-up token twice")
    public void grantsTwiceWithSameToken() {
        Map<String, Object> params = Map.of("amountCents", 500L);
        String token = boundToken("grant_credit", params);
        firstReplayResult = adminService.grantCredit(cmd("grant_credit", token, params));
        secondReplayResult = adminService.grantCredit(cmd("grant_credit", token, params));
    }

    @Then("the first grant succeeds and the second is rejected as a replay")
    public void firstSucceedsSecondReplayed() {
        // Single-use: admin-api tracks the challengeId; the second presentation within the TTL
        // loses the atomic markConsumedOnce race and is rejected as step_up_invalid BEFORE any
        // downstream call is issued.
        assertThat(firstReplayResult.success())
                .as("first presentation of a valid step-up token must succeed")
                .isTrue();
        assertThat(secondReplayResult.success())
                .as("replay of the same step-up token within TTL must be rejected")
                .isFalse();
        assertThat(secondReplayResult.rejectionReason()).isEqualTo("step_up_invalid");
    }

    // ───────────── Group D — Params-hash binding ─────────────

    @When("the admin grants credit with a token whose params-hash does not match the request body")
    public void grantsWithMismatchedParamsHash() {
        // Token is signed for one set of params; the request body presents DIFFERENT params.
        // The signature is good and the action matches, but the params-hash binding catches the
        // swap and rejects (e.g. a $5 token reused to grant $99.99).
        Map<String, Object> tokenParams = Map.of("amountCents", 500L, "reason", "goodwill");
        Map<String, Object> presentedParams = Map.of("amountCents", 9999L, "reason", "goodwill");
        String token = boundToken("grant_credit", tokenParams);
        lastResult = adminService.grantCredit(cmd("grant_credit", token, presentedParams));
    }

    // ───────────── Then (assertions; reached once green) ─────────────

    @Then("the action succeeds")
    public void actionSucceeds() {
        assertThat(lastResult.success()).isTrue();
    }

    @Then("the action is rejected for missing step-up")
    public void rejectedMissingStepUp() {
        assertThat(lastResult.success()).isFalse();
        assertThat(lastResult.rejectionReason()).isEqualTo("step_up_required");
    }

    @Then("the action is rejected for invalid step-up")
    public void rejectedInvalidStepUp() {
        assertThat(lastResult.success()).isFalse();
        assertThat(lastResult.rejectionReason()).isEqualTo("step_up_invalid");
    }

    @Then("an audit entry is recorded")
    public void auditRecorded() {
        assertThat(lastResult.auditEntryId()).isNotNull();
    }

    @Then("the user account status is {string}")
    public void accountStatusIs(String status) {
        lastStatus = adminService.accountStatus(targetId);
        assertThat(lastStatus.accountStatus()).isEqualTo(status);
    }

    @Then("the action is rolled back")
    public void actionRolledBack() {
        assertThat(lastResult.success()).isFalse();
    }
}
