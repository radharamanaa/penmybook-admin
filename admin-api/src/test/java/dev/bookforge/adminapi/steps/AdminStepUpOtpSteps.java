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

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BDD glue for the admin step-up OTP flow (F2): start → verify → use-the-minted-token. Exercises
 * the real Spring Boot server (the JWT filter, {@code @PreAuthorize}, the {@code AdminOtpService}
 * code-verify delegation to the WireMock entitlements OTP store, and the {@code StepUpTokenIssuer}
 * minting), then re-uses the minted token on the gated grant / refund writes.
 */
public class AdminStepUpOtpSteps {

    private static final UUID TARGET_USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestClient restClient = RestClient.create();
    private final AdminParamsHasher paramsHasher = new AdminParamsHasher();

    private String jwt;
    private UUID staffUserId;
    private String action;
    private long amountCents;
    private UUID paymentId;
    private Map<String, Object> stepUpParams;

    private UUID challengeId;
    private String mintedToken;
    private ResponseEntity<String> lastResponse;
    private ResponseEntity<String> verifyResponse;

    @Before
    public void reset() {
        AdminWireMock.resetToCanonicalStubs();
        jwt = null;
        mintedToken = null;
        challengeId = null;
        lastResponse = null;
        verifyResponse = null;
    }

    @Given("a platform admin is signed in for step-up")
    public void platformAdminSignedIn() {
        staffUserId = UUID.randomUUID();
        jwt = UserJwtMinter.forPlatformAdmin(staffUserId);
    }

    @When("they start an OTP challenge for action {string} granting {long} cents")
    public void startGrantChallenge(String action, long amountCents) {
        this.action = action;
        this.amountCents = amountCents;
        this.stepUpParams = new LinkedHashMap<>();
        this.stepUpParams.put("amountCents", amountCents);
        startChallenge();
    }

    @When("they start an OTP challenge for action {string} refunding payment {word} of {long} cents")
    public void startRefundChallenge(String action, String paymentIdRaw, long refundAmountCents) {
        this.action = action;
        this.paymentId = UUID.fromString(paymentIdRaw);
        this.amountCents = refundAmountCents;
        this.stepUpParams = new LinkedHashMap<>();
        this.stepUpParams.put("paymentId", paymentId.toString());
        this.stepUpParams.put("refundAmountCents", refundAmountCents);
        startChallenge();
    }

    private void startChallenge() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("targetUserId", TARGET_USER.toString());
        body.put("params", stepUpParams);
        ResponseEntity<String> response = send(HttpMethod.POST, "/v1/admin/otp/start", body, null);
        assertThat(response.getStatusCode().value())
                .as("otp/start (body=%s)", response.getBody()).isEqualTo(201);
        challengeId = UUID.fromString(parseBodyOf(response).path("challengeId").asText());
    }

    @And("they verify the OTP challenge with the correct code")
    public void verifyCorrectCode() {
        verify(AdminWireMock.OTP_GOOD_CODE);
    }

    @And("they verify the OTP challenge with an incorrect code")
    public void verifyWrongCode() {
        verify("000000");
    }

    private void verify(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId.toString());
        body.put("code", code);
        body.put("action", action);
        body.put("targetUserId", TARGET_USER.toString());
        body.put("params", stepUpParams);
        verifyResponse = send(HttpMethod.POST, "/v1/admin/otp/verify", body, null);
        JsonNode parsed = parseBodyOf(verifyResponse);
        if (parsed.path("verified").asBoolean(false)) {
            mintedToken = parsed.path("stepUpToken").asText(null);
        }
    }

    @Then("a step-up token is returned")
    public void tokenReturned() {
        assertThat(verifyResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(parseBodyOf(verifyResponse).path("verified").asBoolean(false)).isTrue();
        assertThat(mintedToken).as("a verified OTP must mint a token").isNotBlank();
    }

    @And("the token is bound to action {string} for {long} cents")
    public void tokenBoundToAction(String expectedAction, long expectedAmount) {
        String[] parts = mintedToken.split("\\|", -1);
        assertThat(parts).as("token wire form is challengeId|action|paramsHash|exp|sig").hasSize(5);
        assertThat(parts[0]).as("token challenge-id segment is the OTP challenge UUID")
                .isEqualTo(challengeId.toString());
        assertThat(parts[1]).as("token action segment").isEqualTo(expectedAction);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", expectedAmount);
        String expectedHash = paramsHasher.compute(expectedAction, staffUserId, TARGET_USER, params);
        assertThat(parts[2]).as("token paramsHash binds (action, staff, target, {amountCents})")
                .isEqualTo(expectedHash);
    }

    @Then("no step-up token is returned")
    public void noTokenReturned() {
        assertThat(parseBodyOf(verifyResponse).path("verified").asBoolean(true)).isFalse();
        assertThat(mintedToken).isNull();
    }

    @And("the verify failure reason is reported")
    public void failureReasonReported() {
        assertThat(parseBodyOf(verifyResponse).path("failureReason").asText())
                .as("a failed verify carries the entitlements failure reason").isNotEmpty();
    }

    @And("they grant {long} cents using the minted step-up token")
    public void grantWithMintedToken(long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountCents", amountCents);
        lastResponse = send(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/grant-credits", body, mintedToken);
    }

    @And("they refund payment {word} of {long} cents using the minted step-up token")
    public void refundWithMintedToken(String paymentIdRaw, long refundAmountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", paymentIdRaw);
        body.put("amountCents", refundAmountCents);
        body.put("reason", "duplicate charge");
        lastResponse = send(HttpMethod.POST,
                "/v1/admin/users/" + TARGET_USER + "/refund", body, mintedToken);
    }

    @Then("the grant response status is {int}")
    public void grantStatus(int expected) {
        assertThat(lastResponse).isNotNull();
        assertThat(lastResponse.getStatusCode().value())
                .as("body=%s", lastResponse.getBody()).isEqualTo(expected);
    }

    @And("the wallet was credited via entitlements")
    public void walletCredited() {
        AdminWireMock.server().verify(postRequestedFor(urlMatching("/internal/admin/grant_credit"))
                .withRequestBody(matchingJsonPath("$.stepUpChallengeId",
                        equalTo(challengeId.toString())))
                .withRequestBody(matchingJsonPath("$.amountCents", equalTo("500"))));
    }

    @And("the refund was sent to billing with an idempotency key")
    public void refundSentToBilling() {
        // The FULL step-up token now crosses the wire, not just its challenge id: billing verifies
        // and spends the approval itself rather than trusting a caller-named id. Asserting the
        // token STARTS WITH this challenge id proves both that it was forwarded intact and that it
        // is the token minted for this OTP — a bare id assertion could not tell those apart.
        AdminWireMock.server().verify(postRequestedFor(urlMatching("/internal/refund"))
                .withRequestBody(matchingJsonPath("$.stepUpToken",
                        matching(challengeId + "\\|refund\\|.*")))
                .withRequestBody(matchingJsonPath("$.idempotencyKey"))
                .withRequestBody(matchingJsonPath("$.paymentId",
                        equalTo(paymentId.toString()))));
    }

    @And("the refund receipt reports requested and refunded amounts")
    public void receiptReportsAmounts() {
        JsonNode body = parseBodyOf(lastResponse);
        assertThat(body.path("requestedCents").asLong()).isEqualTo(2500L);
        assertThat(body.path("walletDebitedCents").asLong()).isEqualTo(2500L);
        assertThat(body.path("paymentId").asText()).isEqualTo(paymentId.toString());
    }

    // ───────── Internals ─────────

    private ResponseEntity<String> send(HttpMethod method, String path, Map<String, Object> body,
                                        String stepUpToken) {
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
            return restClient.method(method)
                    .uri("http://localhost:" + port + path)
                    .headers(h -> h.addAll(headers))
                    .body(jsonBody)
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
