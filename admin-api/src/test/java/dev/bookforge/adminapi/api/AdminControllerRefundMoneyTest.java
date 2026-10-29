package dev.bookforge.adminapi.api;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.MarkPaidByHandBody;
import dev.bookforge.adminapi.dto.RefundRequest;
import dev.bookforge.adminapi.dto.RefundRequestQueueEntry;
import dev.bookforge.adminapi.service.AdminOtpService;
import dev.bookforge.adminapi.service.AdminService;
import dev.bookforge.adminapi.service.SpendOverviewService;
import dev.bookforge.adminapi.service.audit.AuditEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A1 timeout copy and R3 "mark paid by hand" at the HTTP boundary. */
@ExtendWith(MockitoExtension.class)
class AdminControllerRefundMoneyTest {

    @Mock private DownstreamClient downstream;
    @Mock private AuditEventWriter auditEventWriter;
    @Mock private AdminService adminService;
    @Mock private AdminOtpService adminOtpService;
    @Mock private SpendOverviewService spendOverviewService;

    private AdminController controller;
    private final UUID staff = UUID.randomUUID();
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        controller = new AdminController(downstream, auditEventWriter, adminService,
                adminOtpService, spendOverviewService);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(staff.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void aTimedOutPayoutTellsStaffToCheckThePaymentBeforeRetrying() {
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                false, null, 0L, 0L, false, AdminService.REFUND_OUTCOME_UNKNOWN));

        ResponseEntity<Object> response = controller.refund(UUID.randomUUID(),
                new RefundRequest(UUID.randomUUID(), 2_500L, "admin refund", null),
                "token", jwt, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "refund_outcome_unknown")
                .containsEntry("message",
                        "The refund may have gone through — check the payment before retrying.");
    }

    @Test
    void aTimedOutPayoutLeavesAnAuditRowWithPaymentAndAmount() {
        UUID paymentId = UUID.randomUUID();
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                false, null, 0L, 0L, false, AdminService.REFUND_OUTCOME_UNKNOWN));

        controller.refund(UUID.randomUUID(),
                new RefundRequest(paymentId, 2_500L, "admin refund", null),
                "token", jwt, new MockHttpServletRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> attempted = ArgumentCaptor.forClass(Map.class);
        verify(auditEventWriter).writeDeny(eq(staff), eq("REFUND"), eq("PAYMENT"),
                eq(paymentId.toString()), attempted.capture(), any());
        assertThat(attempted.getValue())
                .containsEntry("paymentId", paymentId.toString())
                .containsEntry("refundAmountCents", 2_500L)
                .containsEntry("reason", "outcome_unknown");
    }

    @Test
    void aPayoutThatNeverReachedBillingSaysNothingWasSent() {
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                false, null, 0L, 0L, false, AdminService.REFUND_NOT_SENT));

        ResponseEntity<Object> response = controller.refund(UUID.randomUUID(),
                new RefundRequest(UUID.randomUUID(), 2_500L, "admin refund", null),
                "token", jwt, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("code", "REFUND_NOT_SENT")
                .containsEntry("message",
                        "Billing could not be reached — nothing was sent. It is safe to retry.");
        org.mockito.Mockito.verifyNoInteractions(auditEventWriter);
    }

    @Test
    void aRetryThatNeverReachedBillingKeepsTheFirstAttemptWarning() {
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                false, null, 0L, 0L, false, AdminService.REFUND_NOT_SENT));

        ResponseEntity<Object> response = controller.refund(UUID.randomUUID(),
                new RefundRequest(UUID.randomUUID(), 2_500L, "admin refund", null, true),
                "token", jwt, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("code", "REFUND_NOT_SENT")
                .containsEntry("message", "This retry did not reach billing — the first attempt may "
                        + "still have gone through. Check the payment, or retry with the same "
                        + "approval (it cannot pay twice).");
    }

    @Test
    void aRefusalWithoutBillingsWordsNamesItsStatusAndSaysNothingWasPaid() {
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                false, null, 0L, 0L, false, null, null, 400));

        ResponseEntity<Object> response = controller.refund(UUID.randomUUID(),
                new RefundRequest(UUID.randomUUID(), 2_500L, "admin refund", null),
                "token", jwt, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("code", "DOWNSTREAM_REJECTED")
                .containsEntry("message", "Billing refused the refund (HTTP 400). Nothing was paid.");
        org.mockito.Mockito.verifyNoInteractions(auditEventWriter);
    }

    @Test
    void aReplayedPayoutIsAuditedAsAReplayNotASecondRefund() {
        UUID paymentId = UUID.randomUUID();
        when(adminService.refund(any())).thenReturn(new AdminService.RefundOutcome(
                true, UUID.randomUUID(), 2_500L, 2_500L, true, null));

        controller.refund(UUID.randomUUID(),
                new RefundRequest(paymentId, 2_500L, "admin refund", null),
                "token", jwt, new MockHttpServletRequest());

        verify(auditEventWriter).writeAllow(eq(staff), eq("REFUND_REPLAY"), eq("PAYMENT"),
                eq(paymentId.toString()), isNull(), anyMap(), any());
    }

    @Test
    void markPaidByHandBindsTheOwnerAsTargetAndAuditsTheSettlement() {
        UUID requestId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        RefundRequestQueueEntry settled = new RefundRequestQueueEntry(requestId, "ORG", orgId,
                "SETTLED", null, "", 0L, 0L, 2_500L, 2_500L, null, null, null, null, null,
                List.of(), null);
        when(adminService.markRefundPaidByHand(any(), any())).thenReturn(
                new AdminService.MarkPaidByHandOutcome(true, settled, null, 0, null, null));

        ResponseEntity<Object> response = controller.markRefundPaidByHand(requestId,
                new MarkPaidByHandBody(orgId, " re_123 ", "paid in Stripe"), "token", jwt,
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<AdminCommand> command = ArgumentCaptor.forClass(AdminCommand.class);
        verify(adminService).markRefundPaidByHand(command.capture(), eq("paid in Stripe"));
        assertThat(command.getValue().targetUserId()).isEqualTo(orgId);
        assertThat(command.getValue().action()).isEqualTo("refund_mark_paid");
        assertThat(command.getValue().params()).containsExactly(
                Map.entry("requestId", requestId.toString()), Map.entry("providerRefundId", "re_123"));
        verify(auditEventWriter).writeAllow(eq(staff), eq("REFUND_MARK_PAID_BY_HAND"),
                eq("REFUND_REQUEST"), eq(requestId.toString()), isNull(), anyMap(), any());
    }

    @Test
    void markPaidByHandShowsBillingsRefusalVerbatim() {
        UUID requestId = UUID.randomUUID();
        String copy = "Stripe hasn't told us about this refund yet — try again in a minute.";
        when(adminService.markRefundPaidByHand(any(), any())).thenReturn(
                new AdminService.MarkPaidByHandOutcome(false, null, null, 409, "refund_not_seen", copy));

        ResponseEntity<Object> response = controller.markRefundPaidByHand(requestId,
                new MarkPaidByHandBody(UUID.randomUUID(), "re_123", null), "token", jwt,
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("message", copy).containsEntry("code", "refund_not_seen");
        verify(auditEventWriter).writeDeny(eq(staff), eq("REFUND_MARK_PAID_BY_HAND"),
                eq("REFUND_REQUEST"), eq(requestId.toString()), anyMap(), any());
    }
}
