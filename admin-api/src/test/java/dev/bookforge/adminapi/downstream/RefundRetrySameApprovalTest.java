package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.bookforge.adminapi.api.AdminController;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.dto.RefundReceiptView;
import dev.bookforge.adminapi.dto.RefundRequest;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.InMemoryStepUpTokenStore;
import dev.bookforge.adminapi.security.StepUpTokenIssuer;
import dev.bookforge.adminapi.service.AdminOtpService;
import dev.bookforge.adminapi.service.AdminService;
import dev.bookforge.adminapi.service.SpendOverviewService;
import dev.bookforge.adminapi.service.StepUpVerifier;
import dev.bookforge.adminapi.service.audit.AuditEventWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * "Retry with the same approval" after a payout timed out: the SPA resends the identical refund
 * with the SAME step-up token. Runs the real controller, service, verifier, params hasher, token
 * store and HTTP client against a stubbed billing, so it proves admin-api does not consume the
 * refund token itself and forwards the same idempotency key both times.
 */
@ExtendWith(MockitoExtension.class)
class RefundRetrySameApprovalTest {

    private static final String SIGNING_KEY = "dev-only-hs256-shared-secret-change-me";
    private static final String REFUND_PATH = "/internal/refund";
    private static final String RETRY_SCENARIO = "retry";
    private static final String AFTER_TIMEOUT = "after-timeout";
    private static final WireMockServer BILLING =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Mock private AuditEventWriter auditEventWriter;
    @Mock private AdminOtpService adminOtpService;
    @Mock private SpendOverviewService spendOverviewService;

    private AdminController controller;
    private final UUID staff = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID refundRequestId = UUID.randomUUID();
    private final String challengeId = UUID.randomUUID().toString();
    private String stepUpToken;
    private Jwt jwt;

    @BeforeAll
    static void start() {
        BILLING.start();
    }

    @AfterAll
    static void stop() {
        BILLING.stop();
    }

    @BeforeEach
    void setUp() {
        BILLING.resetAll();
        ObjectMapper mapper = new ObjectMapper();
        ServiceJwtProperties jwtProperties =
                new ServiceJwtProperties(SIGNING_KEY, "bookforge-internal", "admin-api");
        String base = "http://localhost:" + BILLING.port();
        HttpDownstreamClient downstream = new HttpDownstreamClient(
                new DownstreamProperties(base, base, base, base),
                new ServiceAccountTokenFactory(jwtProperties, mapper),
                mapper, Duration.ofMillis(300));
        AdminParamsHasher hasher = new AdminParamsHasher();
        AdminService adminService = new AdminService(new StepUpVerifier(jwtProperties), hasher,
                new InMemoryStepUpTokenStore(), downstream);
        controller = new AdminController(downstream, auditEventWriter, adminService,
                adminOtpService, spendOverviewService);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(staff.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId.toString());
        params.put("refundAmountCents", 2_500L);
        params.put("refundRequestId", refundRequestId.toString());
        stepUpToken = new StepUpTokenIssuer(jwtProperties)
                .issue(challengeId, "refund", hasher.compute("refund", staff, target, params))
                .token();
    }

    @Test
    void aSameTokenRetryOfARefundThatLandedIsAReplayAuditedAsOne() {
        stubFirstTimesOutThen(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"auditEntryId\":\"" + UUID.randomUUID() + "\","
                        + "\"requestedCents\":2500,\"walletDebitedCents\":2500,\"replayed\":true}"));

        assertThat(payout().getStatusCode().value()).isEqualTo(504);
        ResponseEntity<Object> retry = retry();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(((RefundReceiptView) retry.getBody()).idempotentReplay()).isTrue();
        verify(auditEventWriter).writeAllow(eq(staff), eq("REFUND_REPLAY"), eq("PAYMENT"),
                eq(paymentId.toString()), isNull(), anyMap(), any());
        verify(auditEventWriter, never()).writeAllow(any(), eq("REFUND"), any(), any(), any(),
                anyMap(), any());
        assertBothAttemptsCarriedTheSameApprovalAndKey();
    }

    @Test
    void aSameTokenRetryOfARefundThatNeverLandedPaysOnceAsANormalRefund() {
        stubFirstTimesOutThen(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"auditEntryId\":\"" + UUID.randomUUID() + "\","
                        + "\"requestedCents\":2500,\"walletDebitedCents\":2500,\"replayed\":false}"));

        payout();
        ResponseEntity<Object> retry = retry();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(((RefundReceiptView) retry.getBody()).idempotentReplay()).isFalse();
        verify(auditEventWriter).writeAllow(eq(staff), eq("REFUND"), eq("PAYMENT"),
                eq(paymentId.toString()), isNull(), anyMap(), any());
        assertBothAttemptsCarriedTheSameApprovalAndKey();
    }

    @Test
    void aSameTokenRetryBillingRefusesAsSpentSaysTheApprovalWasUsedUp() {
        stubFirstTimesOutThen(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":403,\"detail\":\"Step-up approval invalid or already used\","
                        + "\"code\":\"step_up_invalid\"}"));

        payout();
        ResponseEntity<Object> retry = retry();

        assertThat(retry.getStatusCode().value()).isEqualTo(409);
        assertThat(asMap(retry.getBody()))
                .containsEntry("code", "REFUND_APPROVAL_USED")
                .containsEntry("message", "This approval was already used — the first attempt may "
                        + "still be going through. Wait a minute and check the payment in Stripe "
                        + "before starting a new refund.");
        assertThat(denyReasons()).containsExactly("outcome_unknown", "approval_used");
        assertBothAttemptsCarriedTheSameApprovalAndKey();
    }

    @Test
    void aFirstAttemptBillingRefusesTheApprovalForIsAPlainStepUpRefusal() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":403,\"code\":\"step_up_invalid\"}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(403);
        assertThat(asMap(first.getBody()))
                .containsEntry("code", "REFUND_STEP_UP_REFUSED")
                .containsEntry("message",
                        "The approval was refused — start the refund again with a new code.");
        assertThat(denyReasons()).containsExactly("step_up_refused");
    }

    @Test
    void aSameTokenRetryThatTimesOutAgainIsStillOutcomeUnknown() {
        stubFirstTimesOutThen(aResponse().withFixedDelay(1_500).withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"success\":true}"));

        payout();
        ResponseEntity<Object> retry = retry();

        assertThat(retry.getStatusCode().value()).isEqualTo(504);
        assertThat(asMap(retry.getBody())).containsEntry("code", "REFUND_OUTCOME_UNKNOWN");
        assertThat(denyReasons()).containsExactly("outcome_unknown", "outcome_unknown");
    }

    @Test
    void aBusinessRefusalOnTheRetryCarriesBillingsMessageVerbatim() {
        stubFirstTimesOutThen(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"detail\":\"nothing refundable\"}"));

        payout();
        ResponseEntity<Object> retry = retry();

        assertThat(retry.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(retry.getBody()))
                .containsEntry("code", "DOWNSTREAM_REJECTED")
                .containsEntry("message", "nothing refundable");
        assertThat(denyReasons()).containsExactly("outcome_unknown");
    }

    @Test
    void aBilling5xxIsAnUnknownOutcomeWithTheSameApprovalRetry() {
        stubFirstAnswerThen(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":500,\"detail\":\"NullPointerException\"}"),
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"requestedCents\":2500,"
                                + "\"walletDebitedCents\":2500,\"replayed\":true}"));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(504);
        assertThat(asMap(first.getBody()))
                .containsEntry("error", "refund_outcome_unknown")
                .containsEntry("cause", "billing_error")
                .containsEntry("message", "Billing hit an error — the refund may have gone "
                        + "through. Check the payment before retrying.");
        assertThat(denyReasons()).containsExactly("outcome_unknown");
        assertThat(((RefundReceiptView) retry().getBody()).idempotentReplay()).isTrue();
        assertBothAttemptsCarriedTheSameApprovalAndKey();
    }

    @Test
    void billingsOutcomeUnknownConflictIsAnUnknownOutcomeQuotingBilling() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":409,\"code\":\"refund_outcome_unknown\","
                        + "\"detail\":\"Stripe refund outcome is unknown\"}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(504);
        assertThat(asMap(first.getBody()))
                .containsEntry("cause", "billing_conflict")
                .containsEntry("message", "Billing could not finish the refund — it may have gone "
                        + "through. Check the payment before retrying.");
        assertThat(denyReasons()).containsExactly("outcome_unknown");
    }

    @Test
    void aRefundCreatedButNotRecordedSaysSoWithoutBillingsFreeText() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":409,\"code\":\"refund_outcome_unknown\","
                        + "\"reason\":\"credit_refused_after_stripe\","
                        + "\"detail\":\"Stripe refund for payment x was created, but ...\"}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(504);
        assertThat(asMap(first.getBody())).containsEntry("message", "Billing could not finish the "
                + "refund — it may have gone through. Check the payment before retrying. Billing "
                + "reported: refund created but could not be recorded against the request.");
    }

    @Test
    void billingsCodedStripeRefusalSaysNothingWasPaidWithNoRetry() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":502,\"code\":\"stripe_refused_refund\","
                        + "\"detail\":\"Stripe refused the refund; nothing was refunded.\"}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(first.getBody()))
                .containsEntry("code", "REFUND_STRIPE_REFUSED")
                .containsEntry("message", "Stripe refused the refund — nothing was paid.");
        assertThat(denyReasons()).isEmpty();
    }

    @Test
    void aBareProxy502IsStillAnUnknownOutcome() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "text/html").withBody("<html>Bad Gateway</html>")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(504);
        assertThat(asMap(first.getBody())).containsEntry("cause", "billing_error");
    }

    @Test
    void theDenyRowRecordsWhetherItWasARetry() {
        stubFirstTimesOutThen(aResponse().withFixedDelay(1_500).withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"success\":true}"));

        payout();
        retry();

        assertThat(denyRetryFlags()).containsExactly(false, true);
    }

    @Test
    void aPlainConflictIsABillingRefusalWordForWordWithNoAudit() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":409,\"detail\":\"Payment was charged back; it cannot be "
                        + "refunded again\"}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(first.getBody()))
                .containsEntry("code", "DOWNSTREAM_REJECTED")
                .containsEntry("message", "Payment was charged back; it cannot be refunded again");
        assertThat(denyReasons()).isEmpty();
    }

    @Test
    void aRefusalWithoutAMessageNamesTheStatusAndSaysNothingWasPaid() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(400)));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(first.getBody())).containsEntry("message",
                "Billing refused the refund (HTTP 400). Nothing was paid.");
        assertThat(denyReasons()).isEmpty();
    }

    @Test
    void aStripeDeclineSaysNothingWasPaid() {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"requestedCents\":2500}")));

        ResponseEntity<Object> first = payout();

        assertThat(first.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(first.getBody()))
                .containsEntry("code", "REFUND_DECLINED")
                .containsEntry("message", "Stripe declined the refund — nothing was paid.");
    }

    private void stubFirstAnswerThen(
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder firstAnswer,
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder retryAnswer) {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).inScenario(RETRY_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(firstAnswer)
                .willSetStateTo(AFTER_TIMEOUT));
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).inScenario(RETRY_SCENARIO)
                .whenScenarioStateIs(AFTER_TIMEOUT)
                .willReturn(retryAnswer));
    }

    private ResponseEntity<Object> payout() {
        return send(false);
    }

    private ResponseEntity<Object> retry() {
        return send(true);
    }

    private ResponseEntity<Object> send(boolean retryOfUnknownOutcome) {
        return controller.refund(target,
                new RefundRequest(paymentId, 2_500L, "admin refund", refundRequestId,
                        retryOfUnknownOutcome),
                stepUpToken, jwt, new MockHttpServletRequest());
    }

    private void stubFirstTimesOutThen(
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder retryAnswer) {
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).inScenario(RETRY_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFixedDelay(1_500).withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"success\":true}"))
                .willSetStateTo(AFTER_TIMEOUT));
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).inScenario(RETRY_SCENARIO)
                .whenScenarioStateIs(AFTER_TIMEOUT)
                .willReturn(retryAnswer));
    }

    private void assertBothAttemptsCarriedTheSameApprovalAndKey() {
        BILLING.verify(2, postRequestedFor(urlEqualTo(REFUND_PATH))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", com.github.tomakehurst.wiremock
                        .client.WireMock.equalTo(challengeId)))
                .withRequestBody(matchingJsonPath("$.stepUpToken", com.github.tomakehurst.wiremock
                        .client.WireMock.equalTo(stepUpToken)))
                .withRequestBody(matchingJsonPath("$.paymentId", com.github.tomakehurst.wiremock
                        .client.WireMock.equalTo(paymentId.toString())))
                .withRequestBody(matchingJsonPath("$.refundRequestId", com.github.tomakehurst.wiremock
                        .client.WireMock.equalTo(refundRequestId.toString()))));
    }

    @SuppressWarnings("unchecked")
    private List<Object> denyRetryFlags() {
        ArgumentCaptor<Map<String, Object>> attempted = ArgumentCaptor.forClass(Map.class);
        verify(auditEventWriter, org.mockito.Mockito.atLeast(0)).writeDeny(eq(staff), eq("REFUND"),
                eq("PAYMENT"), eq(paymentId.toString()), attempted.capture(), any());
        return attempted.getAllValues().stream().map(m -> m.get("retryOfUnknownOutcome")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object> denyReasons() {
        ArgumentCaptor<Map<String, Object>> attempted = ArgumentCaptor.forClass(Map.class);
        verify(auditEventWriter, org.mockito.Mockito.atLeast(0)).writeDeny(eq(staff), eq("REFUND"),
                eq("PAYMENT"), eq(paymentId.toString()), attempted.capture(), any());
        return attempted.getAllValues().stream().map(m -> m.get("reason")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object body) {
        return (Map<String, Object>) body;
    }
}
