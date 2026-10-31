package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.bookforge.adminapi.api.AdminController;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import dev.bookforge.adminapi.dto.RefundRequest;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.InMemoryStepUpTokenStore;
import dev.bookforge.adminapi.security.StepUpTokenIssuer;
import dev.bookforge.adminapi.service.AdminOtpService;
import dev.bookforge.adminapi.service.AdminService;
import dev.bookforge.adminapi.service.MoneyAlertService;
import dev.bookforge.adminapi.service.SpendOverviewService;
import dev.bookforge.adminapi.service.StepUpVerifier;
import dev.bookforge.adminapi.service.audit.AuditEventWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q13 (owner, 30 Oct 2026): "Also freeze this account" on a payout is part of the step-up. A
 * token approved without a freeze cannot be sent with one, a token approved with one cannot be
 * sent without it, and billing gets {@code freezeAccount} exactly when it was approved. Runs the
 * real controller, service, verifier, params hasher and HTTP client against a stubbed billing.
 */
@ExtendWith(MockitoExtension.class)
class RefundFreezeApprovalTest {

    private static final String SIGNING_KEY = "dev-only-hs256-shared-secret-change-me";
    private static final String REFUND_PATH = "/internal/refund";
    private static final WireMockServer BILLING =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Mock private AuditEventWriter auditEventWriter;
    @Mock private AdminOtpService adminOtpService;
    @Mock private SpendOverviewService spendOverviewService;

    private AdminController controller;
    private ServiceJwtProperties jwtProperties;
    private final AdminParamsHasher hasher = new AdminParamsHasher();
    private final UUID staff = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID refundRequestId = UUID.randomUUID();
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
        BILLING.stubFor(post(urlEqualTo(REFUND_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true,\"auditEntryId\":\"" + UUID.randomUUID() + "\","
                        + "\"requestedCents\":2500,\"walletDebitedCents\":2500,\"replayed\":false}")));
        ObjectMapper mapper = new ObjectMapper();
        jwtProperties = new ServiceJwtProperties(SIGNING_KEY, "bookforge-internal", "admin-api");
        String base = "http://localhost:" + BILLING.port();
        HttpDownstreamClient downstream = new HttpDownstreamClient(
                new DownstreamProperties(base, base, base, base),
                new ServiceAccountTokenFactory(jwtProperties, mapper),
                mapper, Duration.ofMillis(300));
        AdminService adminService = new AdminService(new StepUpVerifier(jwtProperties), hasher,
                new InMemoryStepUpTokenStore(), downstream);
        controller = new AdminController(downstream, auditEventWriter, adminService,
                adminOtpService, spendOverviewService, new MoneyAlertService(downstream));
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(staff.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    /** The token the OTP flow mints for the params the SPA started it with. */
    private String approvedToken(boolean freeze) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId.toString());
        params.put("refundAmountCents", 2_500L);
        params.put("refundRequestId", refundRequestId.toString());
        if (freeze) {
            params.put("freezeAccount", true);
        }
        return new StepUpTokenIssuer(jwtProperties)
                .issue(UUID.randomUUID().toString(), "refund",
                        hasher.compute("refund", staff, target, params))
                .token();
    }

    private ResponseEntity<Object> payout(String token, Boolean freezeAccount) {
        return controller.refund(target,
                new RefundRequest(paymentId, 2_500L, "admin refund", refundRequestId, null,
                        freezeAccount),
                token, jwt, new MockHttpServletRequest());
    }

    @Test
    void aNoFreezeApprovalCannotBeSentWithAFreeze() {
        ResponseEntity<Object> response = payout(approvedToken(false), true);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        BILLING.verify(0, postRequestedFor(urlEqualTo(REFUND_PATH)));
    }

    @Test
    void aFreezeApprovalCannotBeSentWithoutTheFreeze() {
        assertThat(payout(approvedToken(true), false).getStatusCode().value()).isEqualTo(403);
        assertThat(payout(approvedToken(true), null).getStatusCode().value()).isEqualTo(403);
        BILLING.verify(0, postRequestedFor(urlEqualTo(REFUND_PATH)));
    }

    @Test
    void aFreezeApprovalReachesBillingWithTheFreeze() {
        ResponseEntity<Object> response = payout(approvedToken(true), true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        BILLING.verify(1, postRequestedFor(urlEqualTo(REFUND_PATH))
                .withRequestBody(matchingJsonPath("$.freezeAccount", equalTo("true"))));
    }

    @Test
    void aNoFreezeApprovalReachesBillingWithNoFreeze() {
        ResponseEntity<Object> response = payout(approvedToken(false), false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        BILLING.verify(1, postRequestedFor(urlEqualTo(REFUND_PATH))
                .withRequestBody(notContaining("freezeAccount")));
    }
}
