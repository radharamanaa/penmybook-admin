package dev.bookforge.adminapi.service;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.downstream.DownstreamResult;
import dev.bookforge.adminapi.downstream.ForceResolveReversedCallResult;
import dev.bookforge.adminapi.downstream.MarkPaidByHandCallResult;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.security.AdminParamsHasher;
import dev.bookforge.adminapi.security.StepUpTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G1: refund / force-resolve tokens are spent in billing, not in admin-api's in-memory store.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceBillingOwnsRefundTokenTest {

    @Mock private StepUpVerifier verifier;
    @Mock private StepUpTokenStore tokenStore;
    @Mock private DownstreamClient downstream;

    private final AdminParamsHasher hasher = new AdminParamsHasher();
    private AdminService adminService;
    private UUID staff;
    private UUID target;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(verifier, hasher, tokenStore, downstream);
        staff = UUID.randomUUID();
        target = UUID.randomUUID();
    }

    @Test
    void refundDoesNotClaimTheTokenLocally() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", UUID.randomUUID().toString());
        params.put("refundAmountCents", 2_500L);
        String hash = hasher.compute("refund", staff, target, params);
        when(verifier.verify("token", "refund"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(downstream.refund(any()))
                .thenReturn(DownstreamResult.refund(true, UUID.randomUUID(), 2_500L, 2_500L, false));

        AdminCommand command = new AdminCommand(staff, target, "refund", "token", params);
        assertThat(adminService.refund(command).success()).isTrue();
        verify(tokenStore, never()).markConsumedOnce(any(), anyLong());
        verify(downstream).refund(command);
    }

    @Test
    void refundRetryAfterDownstream502DoesNotClaimLocally() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", UUID.randomUUID().toString());
        params.put("refundAmountCents", 2_500L);
        String hash = hasher.compute("refund", staff, target, params);
        when(verifier.verify("token", "refund"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(downstream.refund(any()))
                .thenReturn(new DownstreamResult(false, null, null))
                .thenReturn(DownstreamResult.refund(true, UUID.randomUUID(), 2_500L, 2_500L, false));

        AdminCommand command = new AdminCommand(staff, target, "refund", "token", params);
        assertThat(adminService.refund(command).success()).isFalse();
        assertThat(adminService.refund(command).success()).isTrue();
        verify(tokenStore, never()).markConsumedOnce(any(), anyLong());
        verify(downstream, times(2)).refund(command);
    }

    @Test
    void forceResolveDoesNotClaimTheTokenLocally() {
        UUID paymentId = UUID.randomUUID();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId.toString());
        params.put("forceResolve", true);
        params.put("note", "dashboard confirmed reverse");
        String hash = hasher.compute("refund", staff, target, params);
        when(verifier.verify("token", "refund"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(downstream.forceResolveReversed(any()))
                .thenReturn(ForceResolveReversedCallResult.ok(paymentId, 2_500L));

        AdminCommand command = new AdminCommand(staff, target, "refund", "token", params);
        assertThat(adminService.forceResolveReversed(command).success()).isTrue();
        verify(tokenStore, never()).markConsumedOnce(any(), anyLong());
        verify(downstream).forceResolveReversed(command);
    }

    @Test
    void aTimedOutRefundIsOutcomeUnknownNotRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", UUID.randomUUID().toString());
        params.put("refundAmountCents", 2_500L);
        String hash = hasher.compute("refund", staff, target, params);
        when(verifier.verify("token", "refund"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(downstream.refund(any())).thenReturn(DownstreamResult.refundOutcomeUnknown());

        AdminService.RefundOutcome outcome =
                adminService.refund(new AdminCommand(staff, target, "refund", "token", params));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.rejectionReason()).isEqualTo(AdminService.REFUND_OUTCOME_UNKNOWN);
    }

    @Test
    void markPaidByHandLeavesTheTokenForBillingToSpend() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", UUID.randomUUID().toString());
        params.put("providerRefundId", "re_123");
        String hash = hasher.compute("refund_mark_paid", staff, target, params);
        when(verifier.verify("token", "refund_mark_paid"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(downstream.markRefundPaidByHand(any(), eq("note")))
                .thenReturn(MarkPaidByHandCallResult.ok(null));

        AdminCommand command = new AdminCommand(staff, target, "refund_mark_paid", "token", params);
        assertThat(adminService.markRefundPaidByHand(command, "note").success()).isTrue();
        verify(tokenStore, never()).markConsumedOnce(any(), anyLong());
    }

    @Test
    void markPaidByHandRefusesATokenBoundToAnotherRefundId() {
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("requestId", "r1");
        signed.put("providerRefundId", "re_signed");
        String hash = hasher.compute("refund_mark_paid", staff, target, signed);
        when(verifier.verify("token", "refund_mark_paid"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        Map<String, Object> sent = new LinkedHashMap<>(signed);
        sent.put("providerRefundId", "re_other");

        AdminService.MarkPaidByHandOutcome outcome = adminService.markRefundPaidByHand(
                new AdminCommand(staff, target, "refund_mark_paid", "token", sent), null);

        assertThat(outcome.rejectionReason()).isEqualTo("step_up_invalid");
        verify(downstream, never()).markRefundPaidByHand(any(), any());
    }

    @Test
    void grantStillClaimsTheTokenLocally() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("amountCents", 100L);
        String hash = hasher.compute("grant_credit", staff, target, params);
        when(verifier.verify("token", "grant_credit"))
                .thenReturn(new StepUpVerifier.Result.Valid("chal", hash, Instant.now().getEpochSecond() + 60));
        when(tokenStore.markConsumedOnce(eq("chal"), anyLong())).thenReturn(true);
        when(downstream.grantCredit(any()))
                .thenReturn(new DownstreamResult(true, UUID.randomUUID(), null));

        AdminCommand command = new AdminCommand(staff, target, "grant_credit", "token", params);
        assertThat(adminService.grantCredit(command).success()).isTrue();
        verify(tokenStore).markConsumedOnce(eq("chal"), anyLong());
    }
}
