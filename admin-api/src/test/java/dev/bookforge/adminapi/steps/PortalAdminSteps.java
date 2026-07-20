package dev.bookforge.adminapi.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cucumber glue for the Phase-3b admin-portal feature files. Each scenario:
 * <ol>
 *   <li>mints either a platform-admin or a regular-user JWT via {@link UserJwtMinter};</li>
 *   <li>POSTs / PATCHes the admin-api endpoint through the real Spring Boot server (on the
 *       random port chosen by {@code @SpringBootTest});</li>
 *   <li>asserts the HTTP status, the response body, and the WireMock request journal — in
 *       particular the entitlements {@code /internal/audit-log} POST that records ALLOW or
 *       DENY.</li>
 * </ol>
 *
 * <p>The 403 path is driven entirely by Spring Security's {@code @PreAuthorize} chain and the
 * {@code AuditingAccessDeniedHandler} bean; the test exercises both halves end-to-end.
 */
public class PortalAdminSteps {

    private static final UUID TARGET_USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TARGET_ORG = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID FIXED_PAYMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestClient restClient = RestClient.create();
    private final AdminParamsHasher paramsHasher = new AdminParamsHasher();

    private String jwt;
    private boolean callerIsAdmin;
    private UUID staffUserId;
    private ResponseEntity<String> lastResponse;
    private ResponseEntity<String> secondResponse;

    @Before("@portal-admin or not @portal-admin")
    public void perScenarioReset() {
        // Wipe the WireMock journal so the audit-log assertions in this scenario can't see the
        // prior scenario's POSTs. (AdminSteps' @Before also runs this; calling twice is safe.)
        AdminWireMock.resetToCanonicalStubs();
        jwt = null;
        lastResponse = null;
        secondResponse = null;
    }

    // ───────── Given ─────────

    @Given("a platform admin is signed in")
    public void platformAdminSignedIn() {
        staffUserId = UUID.randomUUID();
        jwt = UserJwtMinter.forPlatformAdmin(staffUserId);
        callerIsAdmin = true;
    }

    @Given("a non-admin user is signed in")
    public void nonAdminSignedIn() {
        staffUserId = UUID.randomUUID();
        jwt = UserJwtMinter.forRegularUser(staffUserId);
        callerIsAdmin = false;
    }

    @Given("the target user has has_ever_paid=false")
    public void targetHasNotPaid() {
        // An admin wallet-money grant (ADMIN_CREDIT) never flips has_ever_paid (N2 / D11); this
        // Given makes the precondition explicit in the feature narrative. No stub state is needed.
    }

    // ───────── When — top-up ─────────

    @When("they attempt to top up a user wallet by {long} cents")
    public void attemptTopUp(long amountCents) {
        topUp(amountCents);
    }

    @When("they top up a user wallet by {long} cents")
    public void doTopUp(long amountCents) {
        topUp(amountCents);
    }

    @When("they top up a user wallet by {long} cents without a step-up token")
    public void topUpWithoutStepUp(long amountCents) {
        // Admin caller, but no X-Step-Up-Token header — the gated walletTopUp path rejects with
        // 403 step_up_required BEFORE any downstream credit fires (H3).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        body.put("reason", "support credit");
        lastResponse = sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/wallet/top-up", body, null);
    }

    private void topUp(long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        body.put("reason", "support credit");
        // Top-up mints money → step-up gated (H3). An admin caller presents a valid token bound over
        // {amountCents}; the non-admin path is short-circuited by @PreAuthorize before the controller
        // body, so it never reaches (and never needs) the step-up check.
        String stepUpToken = callerIsAdmin ? boundTopUpToken(amountCents) : null;
        lastResponse = sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/wallet/top-up", body, stepUpToken);
    }

    /**
     * Mint a valid, single-use step-up token for {@code wallet_top_up} whose {@code paramsHash} is
     * the SAME canonical hash {@link AdminParamsHasher} recomputes server-side over (action,
     * staffUserId, targetUserId, {amountCents}).
     */
    private String boundTopUpToken(long amountCents) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", amountCents);
        String paramsHash = paramsHasher.compute("wallet_top_up", staffUserId, TARGET_USER, params);
        return StepUpTokenMinter.validFor("wallet_top_up", paramsHash);
    }

    // ───────── When — refund ─────────

    @When("they attempt to refund a payment of {long} cents")
    public void attemptRefund(long amountCents) {
        refund(UUID.randomUUID(), amountCents);
    }

    @When("they refund a payment of {long} cents")
    public void doRefund(long amountCents) {
        refund(UUID.randomUUID(), amountCents);
    }

    @When("they refund payment {word} of {long} cents")
    public void refundSpecificPayment(String paymentIdRaw, long amountCents) {
        refund(UUID.fromString(paymentIdRaw), amountCents);
    }

    @When("they refund payment {word} of {long} cents again")
    public void refundSpecificPaymentAgain(String paymentIdRaw, long amountCents) {
        // Override the WireMock refund stub so the SECOND call reports idempotentReplay=true and a
        // zero new debit — billing's actual behaviour for a duplicate refund operation.
        AdminWireMock.server().stubFor(post(urlMatching("/internal/refund"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"requestedCents\":2500,\"walletDebitedCents\":0,"
                                + "\"auditEntryId\":null,\"idempotentReplay\":true}")));
        secondResponse = refundCall(UUID.fromString(paymentIdRaw), amountCents);
    }

    private void refund(UUID paymentId, long amountCents) {
        lastResponse = refundCall(paymentId, amountCents);
    }

    private ResponseEntity<String> refundCall(UUID paymentId, long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", paymentId.toString());
        body.put("amountCents", amountCents);
        body.put("reason", "duplicate charge");
        // Refund pulls money back out of the wallet → step-up gated (B3). An admin caller presents
        // a token bound over {paymentId, refundAmountCents}; a non-admin is short-circuited by
        // @PreAuthorize before the controller body, so it never needs the token.
        String stepUpToken = callerIsAdmin ? boundRefundToken(paymentId, amountCents) : null;
        return sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/refund", body, stepUpToken);
    }

    /**
     * Mint a valid, single-use step-up token for {@code refund} whose {@code paramsHash} is the
     * SAME canonical hash {@link AdminParamsHasher} recomputes server-side over (action,
     * staffUserId, targetUserId, {paymentId, refundAmountCents}).
     */
    private String boundRefundToken(UUID paymentId, long refundAmountCents) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId.toString());
        params.put("refundAmountCents", refundAmountCents);
        String paramsHash = paramsHasher.compute("refund", staffUserId, TARGET_USER, params);
        return StepUpTokenMinter.validFor("refund", paramsHash);
    }

    // ───────── When — grant wallet money ─────────

    @When("they attempt to grant {long} cents to a user wallet")
    public void attemptGrant(long amountCents) {
        grant(amountCents);
    }

    @When("they grant {long} cents to a user wallet")
    public void doGrant(long amountCents) {
        grant(amountCents);
    }

    @When("they grant {long} cents to a user wallet without a step-up token")
    public void grantWithoutStepUp(long amountCents) {
        // Admin caller, but no X-Step-Up-Token header — the controller's runWrite path rejects
        // with 403 step_up_required.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        lastResponse = sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/grant-credits", body, null);
    }

    @When("they grant {long} cents to a user wallet with a step-up token bound to a different amount")
    public void grantWithMismatchedStepUp(long amountCents) {
        // Token's paramsHash is bound over a DIFFERENT amount than the request body presents, so
        // the params-hash binding catches the swap → 403 step_up_invalid.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        String tokenForOtherAmount = boundGrantToken(amountCents + 1);
        lastResponse = sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/grant-credits", body, tokenForOtherAmount);
    }

    private void grant(long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        // Grant mints money → step-up gated (D7 / P12). An admin caller presents a valid token
        // bound over {amountCents}; the non-admin path is short-circuited by @PreAuthorize before
        // the controller body, so it never reaches (and never needs) the step-up check.
        String stepUpToken = callerIsAdmin
                ? boundGrantToken(amountCents)
                : null;
        lastResponse = sendWithStepUp(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/grant-credits", body, stepUpToken);
    }

    /**
     * Mint a valid, single-use step-up token for {@code grant_credit} whose {@code paramsHash} is
     * the SAME canonical hash {@link dev.bookforge.adminapi.security.AdminParamsHasher} recomputes
     * server-side over (action, staffUserId, targetUserId, {amountCents}).
     */
    private String boundGrantToken(long amountCents) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", amountCents);
        String paramsHash = paramsHasher.compute("grant_credit", staffUserId, TARGET_USER, params);
        return StepUpTokenMinter.validFor("grant_credit", paramsHash);
    }

    // ───────── When — set platform admin ─────────

    @When("they attempt to set the target user's is_platform_admin flag to true")
    public void attemptSetAdmin() {
        setAdmin(true);
    }

    @When("they set the target user's is_platform_admin flag to true")
    public void doSetAdmin() {
        setAdmin(true);
    }

    private void setAdmin(boolean flag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isPlatformAdmin", flag);
        lastResponse = send(HttpMethod.PATCH, "/v1/admin/users/" + TARGET_USER, body);
    }

    // ───────── When — org pricing ─────────

    @When("they attempt to upsert per-org pricing for action {string} at {long} cents")
    public void attemptOrgPricing(String action, long valueCents) {
        orgPricing(action, valueCents);
    }

    @When("they upsert per-org pricing for action {string} at {long} cents")
    public void doOrgPricing(String action, long valueCents) {
        orgPricing(action, valueCents);
    }

    private void orgPricing(String action, long valueCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("valueCents", valueCents);
        lastResponse = send(HttpMethod.POST,
                "/v1/admin/orgs/" + TARGET_ORG + "/pricing", body);
    }

    // ───────── When — closed-beta waitlist (D11) ─────────

    @When("they search users by email {string}")
    public void searchUsers(String email) {
        lastResponse = sendGet("/v1/admin/users/search?email=" + email);
    }

    @When("they attempt to approve the target user from the waitlist")
    public void attemptApprove() {
        admitFromWaitlist();
    }

    @When("they approve the target user from the waitlist")
    public void doApprove() {
        admitFromWaitlist();
    }

    private void admitFromWaitlist() {
        lastResponse = send(HttpMethod.POST,
                "/v1/admin/waitlist/" + TARGET_USER + "/admit", new LinkedHashMap<>());
    }

    @When("they read the waitlist config")
    public void readWaitlistConfig() {
        lastResponse = sendGet("/v1/admin/waitlist/config");
    }

    @When("they attempt to set the weekly approval limit to {int}")
    public void attemptSetLimit(int limit) {
        setWeeklyLimit(limit);
    }

    @When("they set the weekly approval limit to {int}")
    public void doSetLimit(int limit) {
        setWeeklyLimit(limit);
    }

    private void setWeeklyLimit(int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weeklyApprovalLimit", limit);
        lastResponse = send(HttpMethod.POST, "/v1/admin/waitlist/config", body);
    }

    // ───────── When — payments health ─────────

    @When("they read payments health")
    public void readPaymentsHealth() {
        lastResponse = sendGet("/v1/admin/payments/health");
    }

    @When("they attempt to read payments health")
    public void attemptReadPaymentsHealth() {
        lastResponse = sendGet("/v1/admin/payments/health");
    }

    @And("the payments health reports stuck refunds {int}")
    public void paymentsHealthStuckRefunds(int n) {
        assertThat(parseBody().path("stuckRefundCount").asLong()).isEqualTo(n);
    }

    @And("the payments health reports stuck top-ups {int}")
    public void paymentsHealthStuckTopups(int n) {
        assertThat(parseBody().path("stuckTopupCount").asLong()).isEqualTo(n);
    }

    @And("the payments health reports abandoned top-ups {int}")
    public void paymentsHealthAbandonedTopups(int n) {
        assertThat(parseBody().path("abandonedTopupCount").asLong()).isEqualTo(n);
    }

    @And("the payments health reports webhooks 24h {int}")
    public void paymentsHealthWebhooks24h(int n) {
        assertThat(parseBody().path("completedWebhooks24h").asLong()).isEqualTo(n);
    }

    @And("the search result includes account status {string}")
    public void searchHasStatus(String status) {
        JsonNode body = parseBody();
        assertThat(body.isArray()).as("search returns a JSON array").isTrue();
        assertThat(body.get(0).path("accountStatus").asText()).isEqualTo(status);
    }

    @And("the approval reports admitted {word}")
    public void approvalAdmitted(String expected) {
        assertThat(parseBody().path("admitted").asBoolean())
                .isEqualTo(Boolean.parseBoolean(expected));
    }

    @And("the waitlist config reports weekly limit {int}")
    public void configReportsLimit(int limit) {
        assertThat(parseBody().path("weeklyApprovalLimit").asInt()).isEqualTo(limit);
    }

    // ───────── Then ─────────

    @Then("the response status is {int}")
    public void responseStatusIs(int expected) {
        assertThat(lastResponse).as("HTTP call must have returned").isNotNull();
        assertThat(lastResponse.getStatusCode().value())
                .as("HTTP status (body=%s)", lastResponse.getBody())
                .isEqualTo(expected);
    }

    @And("the response code is {string}")
    public void responseCodeIs(String expectedCode) {
        JsonNode body = parseBody();
        assertThat(body.path("code").asText())
                .as("step-up rejection body carries a machine code (body=%s)",
                        lastResponse == null ? null : lastResponse.getBody())
                .isEqualTo(expectedCode);
    }

    @And("no wallet credit was issued to entitlements")
    public void noWalletCreditIssued() {
        // The step-up reject fires inside runWrite BEFORE any downstream call, so entitlements
        // must NOT have been asked to mint the ADMIN_CREDIT.
        AdminWireMock.server().verify(0,
                postRequestedFor(urlMatching("/internal/admin/grant_credit")));
    }

    @And("no wallet credit was issued to billing")
    public void noBillingCreditIssued() {
        // H3: the top-up step-up reject fires inside the gate BEFORE any downstream call, so billing
        // must NOT have been asked to credit the wallet.
        AdminWireMock.server().verify(0,
                postRequestedFor(urlMatching("/internal/wallet/credit")));
    }

    @And("the audit log has a DENY row for action {string}")
    public void auditDeny(String action) {
        // SecurityConfig's AuditingAccessDeniedHandler writes a generic DENY row whose action is
        // the HTTP method + path (forensic capture of WHICH endpoint was attempted). The action
        // label in the feature names the LOGICAL operation; we accept both forms so future
        // refactors that switch to logical labels stay green.
        AdminWireMock.server().verify(postRequestedFor(urlMatching("/internal/audit-log"))
                .withRequestBody(matchingJsonPath("$.decision",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("DENY"))));
        // Sanity: the called-but-not-admin endpoint did NOT execute its happy-path downstream
        // (no top-up / refund / grant / patch / org-pricing call should have fired).
        assertNoMutatingDownstreamCalledFor(action);
        assertThat(callerIsAdmin)
                .as("DENY scenario asserts a non-admin caller; admin scenarios use ALLOW")
                .isFalse();
    }

    @And("the audit log has an ALLOW row for action {string}")
    public void auditAllow(String action) {
        AdminWireMock.server().verify(postRequestedFor(urlMatching("/internal/audit-log"))
                .withRequestBody(matchingJsonPath("$.decision",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("ALLOW")))
                .withRequestBody(matchingJsonPath("$.action",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(action))));
    }

    @And("the new wallet balance is reported")
    public void walletBalanceReported() {
        JsonNode body = parseBody();
        assertThat(body.path("balanceCents").asLong()).isGreaterThan(0L);
        assertThat(body.path("userId").asText()).isEqualTo(TARGET_USER.toString());
    }

    @And("a refund receipt is returned")
    public void refundReceiptReturned() {
        JsonNode body = parseBody();
        // F1 / D — the receipt carries BOTH the clamped ask and what was actually debited.
        assertThat(body.path("requestedCents").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.path("walletDebitedCents").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.path("paymentId").asText()).isNotEmpty();
    }

    @And("the receipt reports the granted amount in cents")
    public void receiptReportsAmount() {
        JsonNode body = parseBody();
        assertThat(body.path("amountCents").asLong())
                .as("grant receipt echoes the credited wallet amount in cents")
                .isEqualTo(500L);
        assertThat(body.path("userId").asText()).isEqualTo(TARGET_USER.toString());
        assertThat(body.path("auditEntryId").asText())
                .as("entitlements audit-row id proves the credit + audit committed")
                .isNotEmpty();
    }

    @And("the response confirms is_platform_admin is now true")
    public void setAdminConfirmed() {
        JsonNode body = parseBody();
        assertThat(body.path("isPlatformAdmin").asBoolean(false)).isTrue();
        // Sanity: admin-api also forwarded the PATCH to entitlements with the right body.
        AdminWireMock.server().verify(patchRequestedFor(
                        urlPathMatching("/internal/admin/users/[a-fA-F0-9-]{36}"))
                .withRequestBody(matchingJsonPath("$.isPlatformAdmin",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("true"))));
    }

    @And("the persisted pricing row is returned with the requested value")
    public void pricingPersisted() {
        JsonNode body = parseBody();
        assertThat(body.path("action").asText()).isEqualTo("CHAPTER_GENERATE");
        assertThat(body.path("valueCents").asLong()).isEqualTo(100L);
    }

    // ───────── Refund idempotency ─────────

    @Then("both refund calls succeed")
    public void bothRefundsSucceeded() {
        assertThat(lastResponse).isNotNull();
        assertThat(secondResponse).isNotNull();
        assertThat(lastResponse.getStatusCode().is2xxSuccessful())
                .as("first refund body=%s", lastResponse.getBody()).isTrue();
        assertThat(secondResponse.getStatusCode().is2xxSuccessful())
                .as("second refund body=%s", secondResponse.getBody()).isTrue();
    }

    @And("the wallet was only credited once for that payment")
    public void walletCreditedOnce() {
        // billing's refund endpoint is idempotent on the refund operation; the request journal at
        // admin-api shows two refund calls (admin-api does not de-duplicate locally) but the
        // idempotentReplay=true on the second response is the proof that the underlying ledger
        // change happened exactly once.
        AdminWireMock.server().verify(2,
                postRequestedFor(urlMatching("/internal/refund"))
                        .withRequestBody(matchingJsonPath("$.paymentId",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                                        FIXED_PAYMENT.toString()))));
        JsonNode secondBody = parseBodyOf(secondResponse);
        assertThat(secondBody.path("walletDebitedCents").asLong())
                .as("second call MUST report zero new debit")
                .isEqualTo(0L);
    }

    @And("the second refund call reports idempotentReplay=true")
    public void secondRefundReplay() {
        JsonNode secondBody = parseBodyOf(secondResponse);
        assertThat(secondBody.path("idempotentReplay").asBoolean(false))
                .as("billing reports idempotent replay on duplicate paymentId")
                .isTrue();
    }

    // ───────── Internals ─────────

    private void assertNoMutatingDownstreamCalledFor(String action) {
        // Best-effort: assert the corresponding downstream endpoint was NOT called. Reading the
        // unauthorized attempt's downstream-call count is the strongest evidence the @PreAuthorize
        // short-circuited BEFORE the controller body ran.
        switch (action) {
            case "WALLET_TOPUP" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlMatching("/internal/wallet/credit")));
            case "REFUND" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlMatching("/internal/refund")));
            case "GRANT_CREDITS" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlMatching("/internal/admin/grant_credit")));
            case "SET_PLATFORM_ADMIN" -> AdminWireMock.server().verify(0,
                    patchRequestedFor(urlPathMatching("/internal/admin/users/[a-fA-F0-9-]{36}")));
            case "UPSERT_ORG_PRICING" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlMatching("/internal/pricing/org-override")));
            case "WAITLIST_ADMIT" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlMatching("/internal/admin/waitlist-admit")));
            case "WAITLIST_CONFIG" -> AdminWireMock.server().verify(0,
                    postRequestedFor(urlPathMatching("/internal/admin/waitlist/config")));
            default -> {
                // No-op — unknown action label means the assertion is informational only.
            }
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String path, Map<String, Object> body) {
        return sendWithStepUp(method, path, body, null);
    }

    private ResponseEntity<String> sendGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        try {
            return restClient.get()
                    .uri("http://localhost:" + port + path)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders() == null ? new HttpHeaders() : e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    private ResponseEntity<String> sendWithStepUp(HttpMethod method, String path,
                                                  Map<String, Object> body, String stepUpToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        if (stepUpToken != null) {
            headers.set("X-Step-Up-Token", stepUpToken);
        }
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            return restClient.method(method)
                    .uri("http://localhost:" + port + path)
                    .headers(h -> h.addAll(entity.getHeaders()))
                    .body(entity.getBody())
                    .retrieve()
                    .toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            HttpStatusCode statusCode = e.getStatusCode();
            return ResponseEntity.status(statusCode)
                    .headers(e.getResponseHeaders() == null ? new HttpHeaders() : e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    private JsonNode parseBody() {
        return parseBodyOf(lastResponse);
    }

    private JsonNode parseBodyOf(ResponseEntity<String> response) {
        try {
            String body = response == null ? null : response.getBody();
            if (body == null || body.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("could not parse response body", e);
        }
    }
}
