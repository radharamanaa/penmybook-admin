package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.bookforge.adminapi.downstream.DownstreamResult.RefundFailure;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.dto.AdminCommand;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The billing money calls made by {@link HttpDownstreamClient}: payout timeout (A1), top-up
 * idempotency (A3) and "mark paid by hand" (R3).
 */
class HttpDownstreamClientMoneyCallsTest {

    private static final WireMockServer BILLING =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String CHALLENGE_ID = "7d3e2f0a-1111-4222-8333-944455556666";
    private static final String TOKEN = CHALLENGE_ID + "|refund|hash|9999999999|sig";

    private HttpDownstreamClient client;

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
        String base = "http://localhost:" + BILLING.port();
        client = new HttpDownstreamClient(
                new DownstreamProperties(base, base, base, base),
                new ServiceAccountTokenFactory(
                        new ServiceJwtProperties("dev-only-hs256-shared-secret-change-me",
                                "bookforge-internal", "admin-api"), mapper),
                mapper, Duration.ofMillis(300));
    }

    @Test
    void payoutBudgetCoversAStripeCall() {
        assertThat(HttpDownstreamClient.REFUND_PAYOUT_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aPayoutThatTimesOutIsReportedAsOutcomeUnknownNotRejected() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withFixedDelay(1_500).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aPayoutBillingAnswers5xxIsABillingErrorNotARefusal() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(500)));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);
    }

    @Test
    void aConnectTimeoutIsAPlainFailureNotOutcomeUnknown() {
        DownstreamCallException connect = new DownstreamCallException("x",
                new java.net.http.HttpConnectTimeoutException("connect timed out"));
        DownstreamCallException read = new DownstreamCallException("x",
                new java.net.http.HttpTimeoutException("request timed out"));

        assertThat(connect.timedOut()).isFalse();
        assertThat(read.timedOut()).isTrue();
    }

    @Test
    void aPayoutThatCannotConnectIsAPlainFailure() {
        String unroutable = "http://10.255.255.1:81";
        ObjectMapper mapper = new ObjectMapper();
        HttpDownstreamClient unreachable = new HttpDownstreamClient(
                new DownstreamProperties(unroutable, unroutable, unroutable, unroutable),
                new ServiceAccountTokenFactory(
                        new ServiceJwtProperties("dev-only-hs256-shared-secret-change-me",
                                "bookforge-internal", "admin-api"), mapper),
                mapper, Duration.ofSeconds(30));

        DownstreamResult result = unreachable.refund(refundCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.refundFailure()).isEqualTo(RefundFailure.NOT_SENT);
    }

    @Test
    void aReplayedPayoutIsReadFromBillingsReplayedField() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"requestedCents\":2500,"
                                + "\"walletDebitedCents\":2500,\"replayed\":true}")));

        assertThat(client.refund(refundCommand()).idempotentReplay()).isTrue();
    }

    @Test
    void aPayoutWhoseApprovalBillingRefusesIsReportedAsStepUpRefused() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":403,\"code\":\"step_up_invalid\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.refundFailure()).isEqualTo(RefundFailure.STEP_UP_REFUSED);
        assertThat(result.refundFailure()).isNotEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aForbiddenPayoutWithAnotherCodeIsAPlainFailure() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":403,\"code\":\"owner_mismatch\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.REFUSED);
        assertThat(result.refusalStatus()).isEqualTo(403);
        assertThat(result.refusalDetail()).isNull();
    }

    @Test
    void aForbiddenPayoutWithoutACodeIsAPlainFailure() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund")).willReturn(aResponse().withStatus(403)));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.REFUSED);
    }

    @Test
    void aStepUpInvalidCodeOnAnotherStatusIsAPlainFailure() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":400,\"code\":\"step_up_invalid\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.REFUSED);
    }

    @Test
    void aResentPayoutForwardsTheSameIdempotencyKeyAndToken() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"replayed\":true}")));
        AdminCommand command = refundCommand();

        client.refund(command);
        client.refund(command);

        BILLING.verify(2, postRequestedFor(urlEqualTo("/internal/refund"))
                .withRequestBody(containing("\"idempotencyKey\":\"" + CHALLENGE_ID + "\""))
                .withRequestBody(containing("\"stepUpToken\":\"" + TOKEN + "\"")));
    }

    @Test
    void aConnectionResetAfterSendingIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
        assertThat(result.refundFailure()).isNotEqualTo(RefundFailure.NOT_SENT);
    }

    @Test
    void anEmptyResponseAfterSendingIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aGarbledSuccessBodyIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":tru")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aSuccessBodyWithoutAResultIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"requestedCents\":2500}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void anEmptySuccessBodyIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aSuccessFalseAnswerIsStripeDeclinedNotUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.DECLINED);
    }

    @Test
    void aPayoutBillingRefusesWithAMessageCarriesItVerbatim() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":422,\"detail\":\"Nothing left to refund on this payment.\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(DownstreamResult.RefundFailure.REFUSED);
        assertThat(result.refusalDetail()).isEqualTo("Nothing left to refund on this payment.");
        assertThat(result.refusalStatus()).isEqualTo(422);
    }

    @Test
    void aPlainConflictIsARefusalBeforeStripeCarriedVerbatim() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"detail\":\"Check the adjustment in Stripe.\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.REFUSED);
        assertThat(result.refusalStatus()).isEqualTo(409);
        assertThat(result.refusalDetail()).isEqualTo("Check the adjustment in Stripe.");
    }

    @Test
    void aConflictWithoutAMessageIsARefusalNamingItsStatus() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund")).willReturn(aResponse().withStatus(409)));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.REFUSED);
        assertThat(result.refusalStatus()).isEqualTo(409);
        assertThat(result.refusalDetail()).isNull();
    }

    @Test
    void aServerErrorMessageIsABillingErrorWithoutItsText() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":500,\"detail\":\"NullPointerException at ...\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);
        assertThat(result.refusalDetail()).isNull();
    }

    @Test
    void billingsOutcomeUnknownConflictIsUnsettled() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"code\":\"refund_outcome_unknown\","
                                + "\"detail\":\"Stripe refund outcome is unknown\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.BILLING_CONFLICT);
    }

    @Test
    void billingsOutcomeUnknownReasonIsCarriedNotItsFreeText() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"code\":\"refund_outcome_unknown\","
                                + "\"reason\":\"credit_refused_after_stripe\",\"detail\":\"free text\"}")));

        assertThat(client.refund(refundCommand()).refusalDetail()).isEqualTo("credit_refused_after_stripe");
    }

    @Test
    void billingsCodedStripeRefusalIsStripeRefused() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(502)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":502,\"code\":\"stripe_refused_refund\","
                                + "\"detail\":\"Stripe refused the refund; nothing was refunded.\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.STRIPE_REFUSED);
    }

    @Test
    void aStripeRefusedCodeWithoutBillingsDetailIsNotTrusted() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(502)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":502,\"code\":\"stripe_refused_refund\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);
    }

    @Test
    void aStripeRefusedCodeOnAnotherStatusIsNotTrusted() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":503,\"code\":\"stripe_refused_refund\",\"detail\":\"x\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);
    }

    @Test
    void aBareProxy502Or503IsABillingError() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(502).withBody("<html>Bad Gateway</html>")));
        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);

        BILLING.resetAll();
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":503,\"detail\":\"Payment provider unavailable\"}")));
        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.BILLING_ERROR);
    }

    @Test
    void aFrozenWalletConflictIsARefusalBeforeAnyMoneyMoved() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"code\":\"wallet_frozen\","
                                + "\"detail\":\"This account is under review.\"}")));

        DownstreamResult result = client.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.REFUSED);
        assertThat(result.refusalDetail()).isEqualTo("This account is under review.");
    }

    @Test
    void aSuccessWithAMalformedAuditIdIsOutcomeUnknown() {
        BILLING.stubFor(post(urlEqualTo("/internal/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"auditEntryId\":\"not-a-uuid\"}")));

        assertThat(client.refund(refundCommand()).refundFailure()).isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void aPayoutWhoseConnectionIsRefusedIsReportedAsNotSent() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String refused = "http://localhost:" + closedPort;
        ObjectMapper mapper = new ObjectMapper();
        HttpDownstreamClient unreachable = new HttpDownstreamClient(
                new DownstreamProperties(refused, refused, refused, refused),
                new ServiceAccountTokenFactory(
                        new ServiceJwtProperties("dev-only-hs256-shared-secret-change-me",
                                "bookforge-internal", "admin-api"), mapper),
                mapper, Duration.ofSeconds(5));

        DownstreamResult result = unreachable.refund(refundCommand());

        assertThat(result.refundFailure()).isEqualTo(RefundFailure.NOT_SENT);
        assertThat(result.refundFailure()).isNotEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void anOpenBreakerIsNotSentButAnotherFallbackIsOutcomeUnknown() {
        io.github.resilience4j.circuitbreaker.CallNotPermittedException open =
                io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("billing"));

        assertThat(client.refundFallback(refundCommand(), open).refundFailure()).isEqualTo(RefundFailure.NOT_SENT);
        assertThat(client.refundFallback(refundCommand(), new IllegalStateException("x")).refundFailure())
                .isEqualTo(RefundFailure.OUTCOME_UNKNOWN);
    }

    @Test
    void topUpWithoutAStepUpTokenIsRefusedNotKeyedOnNull() {
        assertThatThrownBy(() -> client.walletTopUp(UUID.randomUUID(), 500L, "admin top-up", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.walletTopUp(UUID.randomUUID(), 500L, "admin top-up", " "))
                .isInstanceOf(IllegalStateException.class);
        BILLING.verify(0, postRequestedFor(urlEqualTo("/internal/wallet/credit")));
    }

    @Test
    void topUpSendsTheChallengeIdAsTheIdempotencyKey() {
        BILLING.stubFor(post(urlEqualTo("/internal/wallet/credit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"balanceCents\":1500}")));

        client.walletTopUp(UUID.randomUUID(), 500L, "admin top-up", TOKEN);

        BILLING.verify(postRequestedFor(urlEqualTo("/internal/wallet/credit"))
                .withRequestBody(containing("\"idempotencyKey\":\"admin-topup:" + CHALLENGE_ID + "\"")));
    }

    @Test
    void markPaidByHandForwardsTheTokenAndReturnsTheSettledRequest() {
        UUID requestId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        BILLING.stubFor(post(urlEqualTo(markPaidPath(requestId)))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + requestId + "\",\"ownerType\":\"ORG\",\"ownerId\":\""
                                + ownerId + "\",\"status\":\"SETTLED\",\"refundedCents\":2500}")));
        UUID staff = UUID.randomUUID();

        MarkPaidByHandCallResult result = client.markRefundPaidByHand(
                markPaidCommand(staff, ownerId, requestId), "paid in the Dashboard");

        assertThat(result.success()).isTrue();
        assertThat(result.request().status()).isEqualTo("SETTLED");
        assertThat(result.request().refundedCents()).isEqualTo(2500L);
        BILLING.verify(postRequestedFor(urlEqualTo(markPaidPath(requestId)))
                .withRequestBody(equalToJson("{\"staffUserId\":\"" + staff + "\","
                        + "\"providerRefundId\":\"re_123\",\"note\":\"paid in the Dashboard\","
                        + "\"stepUpToken\":\"" + TOKEN + "\"}")));
    }

    @Test
    void markPaidByHandCarriesBillingsRefusalVerbatim() {
        UUID requestId = UUID.randomUUID();
        BILLING.stubFor(post(urlEqualTo(markPaidPath(requestId)))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":409,\"detail\":\"Stripe hasn't told us about this "
                                + "refund yet — try again in a minute.\",\"code\":\"refund_not_seen\"}")));

        MarkPaidByHandCallResult result = client.markRefundPaidByHand(
                markPaidCommand(UUID.randomUUID(), UUID.randomUUID(), requestId), null);

        assertThat(result.success()).isFalse();
        assertThat(result.refusedStatus()).isEqualTo(409);
        assertThat(result.refusedCode()).isEqualTo("refund_not_seen");
        assertThat(result.refusedMessage())
                .isEqualTo("Stripe hasn't told us about this refund yet — try again in a minute.");
    }

    @Test
    void markPaidByHandOutageIsNotARefusal() {
        UUID requestId = UUID.randomUUID();
        BILLING.stubFor(post(urlEqualTo(markPaidPath(requestId)))
                .willReturn(aResponse().withStatus(503)));

        MarkPaidByHandCallResult result = client.markRefundPaidByHand(
                markPaidCommand(UUID.randomUUID(), UUID.randomUUID(), requestId), null);

        assertThat(result.success()).isFalse();
        assertThat(result.refusedStatus()).isZero();
    }

    private static String markPaidPath(UUID requestId) {
        return "/internal/admin/refund-requests/" + requestId + "/mark-paid-by-hand";
    }

    private static AdminCommand refundCommand() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", UUID.randomUUID().toString());
        params.put("refundAmountCents", 2_500L);
        return new AdminCommand(UUID.randomUUID(), UUID.randomUUID(), "refund", TOKEN, params);
    }

    private static AdminCommand markPaidCommand(UUID staff, UUID ownerId, UUID requestId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId.toString());
        params.put("providerRefundId", "re_123");
        return new AdminCommand(staff, ownerId, "refund_mark_paid", TOKEN, params);
    }
}
