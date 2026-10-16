package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.dto.AccountStatusView;
import dev.bookforge.adminapi.dto.AdminCommand;
import dev.bookforge.adminapi.dto.GlobalWalletSummaryView;
import dev.bookforge.adminapi.dto.MoneyFlowsView;
import dev.bookforge.adminapi.dto.OrgPricingRuleView;
import dev.bookforge.adminapi.dto.OrgView;
import dev.bookforge.adminapi.dto.PaymentHealthView;
import dev.bookforge.adminapi.dto.PlatformAdminUpdateView;
import dev.bookforge.adminapi.dto.RefundRequestQueueEntry;
import dev.bookforge.adminapi.dto.RefundRequestQueueView;
import dev.bookforge.adminapi.dto.RefundResidualEntry;
import dev.bookforge.adminapi.dto.RefundResidualQueueView;
import dev.bookforge.adminapi.dto.SpendActionEntry;
import dev.bookforge.adminapi.dto.SpendByBookChargeView;
import dev.bookforge.adminapi.dto.SpendByBooksResponse;
import dev.bookforge.adminapi.dto.SpendReportView;
import dev.bookforge.adminapi.dto.UserSearchResultEntry;
import dev.bookforge.adminapi.dto.PreAdmitEntryView;
import dev.bookforge.adminapi.dto.PreAdmitView;
import dev.bookforge.adminapi.dto.WaitlistAdmitView;
import dev.bookforge.adminapi.dto.WaitlistConfigView;
import dev.bookforge.adminapi.dto.WaitlistEntryView;
import dev.bookforge.adminapi.dto.WalletBalanceView;
import dev.bookforge.adminapi.dto.WalletSummaryView;
import dev.bookforge.adminapi.service.audit.AuditLogEntry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP adapter to the internal mesh. Posts the exact frozen request bodies from
 * contracts/internal-api.md to entitlements / billing, carrying a per-callee service-account JWT.
 * Each downstream callee performs the state change AND its audit row in one transaction, so we
 * simply relay {@code success} + {@code auditEntryId} back up.
 *
 * <p>Resilience4j circuit breakers (named {@code entitlements} and {@code billing}, configured in
 * {@code application.yml}) wrap the new Phase-3b admin-portal calls so a downstream outage
 * short-circuits to a fast 503 at the controller rather than tying up admin-portal request
 * threads during an incident.
 */
@Component
public class HttpDownstreamClient implements DownstreamClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(HttpDownstreamClient.class);

    private static final String ENTITLEMENTS_AUD = "entitlements";
    private static final String BILLING_AUD = "billing";
    private static final String ORG_AUD = "org";
    /** Step-up-gated writes (Phase 3a) keep the 1-second RPC budget from spec 05 §5 #9. */
    private static final Duration STEP_UP_RPC_TIMEOUT = Duration.ofSeconds(1);
    /** Phase-3b admin-portal calls use the 5-second budget mandated by the task spec. */
    private static final Duration ADMIN_RPC_TIMEOUT = Duration.ofSeconds(5);
    /**
     * Approve fans out one Stripe refund per payment (15s budget each). Three typical top-ups
     * need ~30s; a 5s timeout here is the double-refund path (billing settles APPROVED, we
     * DENY, the operator retries via POST /users/{id}/refund).
     */
    static final Duration REFUND_RESOLVE_TIMEOUT = Duration.ofSeconds(30);
    /** Per-book charges batch: tight budget; fail-soft on timeout so Analytics stays up. */
    private static final Duration BY_BOOKS_RPC_TIMEOUT = Duration.ofSeconds(2);
    /**
     * Identity enrichment for the refund queue. Concurrent with a 2s budget so billing's 5s
     * plus names cannot exceed the SPA's 12s abort (serial 5+5+5 would).
     */
    static final Duration IDENTITY_RPC_TIMEOUT = Duration.ofSeconds(2);
    /** Circuit-breaker instance names — must match {@code application.yml}. */
    static final String CB_ENTITLEMENTS = "entitlements";
    static final String CB_BILLING = "billing";
    /** Dedicated report-read breakers — a slow dashboard must not open the money-op breakers. */
    static final String CB_ENTITLEMENTS_REPORTS = "entitlements-reports";
    static final String CB_BILLING_REPORTS = "billing-reports";

    private final DownstreamProperties downstream;
    private final ServiceAccountTokenFactory tokenFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpDownstreamClient(DownstreamProperties downstream,
                                ServiceAccountTokenFactory tokenFactory,
                                ObjectMapper objectMapper) {
        this.downstream = downstream;
        this.tokenFactory = tokenFactory;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(ADMIN_RPC_TIMEOUT).build();
    }

    // ───────── Phase 3a step-up-gated writes ─────────

    @Override
    public DownstreamResult grantCredit(AdminCommand command) {
        // Unified-wallet money model (D7 / P12 / UF-04): grant credits = credit the user's
        // personal wallet by amountCents (an ADMIN_CREDIT credit), owner-scoped. Mirrors the
        // frozen contract body in contracts/internal-api.md.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", command.targetUserId().toString());
        body.put("ownerType", "USER");
        body.put("ownerId", command.targetUserId().toString());
        body.put("amountCents", command.params().get("amountCents"));
        body.put("reason", command.params().getOrDefault("reason", "goodwill"));
        body.put("staffUserId", command.staffUserId().toString());
        body.put("stepUpChallengeId", challengeIdOf(command.stepUpToken()));
        return postLegacy(downstream.entitlementsUrl() + "/internal/admin/grant_credit",
                ENTITLEMENTS_AUD, body);
    }

    @Override
    public DownstreamResult suspend(AdminCommand command) {
        return postLegacy(downstream.entitlementsUrl() + "/internal/admin/suspend", ENTITLEMENTS_AUD,
                statusChangeBody(command, null));
    }

    @Override
    public DownstreamResult unsuspend(AdminCommand command) {
        return postLegacy(downstream.entitlementsUrl() + "/internal/admin/unsuspend", ENTITLEMENTS_AUD,
                statusChangeBody(command, null));
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public List<WaitlistEntryView> waitlistQueue(int limit) {
        String url = downstream.entitlementsUrl() + "/internal/admin/waitlist?limit=" + limit;
        JsonNode json = get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT);
        List<WaitlistEntryView> out = new ArrayList<>();
        if (json != null && json.isArray()) {
            for (JsonNode row : json) {
                UUID userId = optionalUuid(row, "userId");
                String email = textOrNull(row, "email");
                String createdAtRaw = textOrNull(row, "createdAt");
                Instant createdAt = createdAtRaw == null ? null : Instant.parse(createdAtRaw);
                out.add(new WaitlistEntryView(userId, email, createdAt));
            }
        }
        return out;
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public WaitlistAdmitView waitlistAdmit(UUID targetUserId, UUID staffUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", targetUserId.toString());
        body.put("staffUserId", staffUserId.toString());
        String url = downstream.entitlementsUrl() + "/internal/admin/waitlist-admit";
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        return new WaitlistAdmitView(
                json.path("admitted").asBoolean(false),
                textOrNull(json, "accountStatus"),
                textOrNull(json, "reason"));
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public WaitlistConfigView waitlistConfig() {
        String url = downstream.entitlementsUrl() + "/internal/admin/waitlist/config";
        JsonNode json = get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT);
        return parseWaitlistConfig(json);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public WaitlistConfigView updateWaitlistConfig(Boolean enabled, Integer weeklyApprovalLimit,
                                                   String updatedBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        if (weeklyApprovalLimit != null) {
            body.put("weeklyApprovalLimit", weeklyApprovalLimit);
        }
        body.put("updatedBy", updatedBy);
        String url = downstream.entitlementsUrl() + "/internal/admin/waitlist/config";
        return parseWaitlistConfig(send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT));
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public PreAdmitView preAdmitEmail(String email, UUID staffUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("staffUserId", staffUserId.toString());
        String url = downstream.entitlementsUrl() + "/internal/admin/pre-admit";
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        return new PreAdmitView(json.path("admitted").asBoolean(false), textOrNull(json, "reason"),
                parseNullableInstant(json, "expiresAt"));
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public List<PreAdmitEntryView> listPreAdmits() {
        String url = downstream.entitlementsUrl() + "/internal/admin/pre-admits";
        JsonNode json = get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT);
        List<PreAdmitEntryView> out = new ArrayList<>();
        if (json != null && json.isArray()) {
            for (JsonNode row : json) {
                out.add(new PreAdmitEntryView(
                        optionalUuid(row, "id"),
                        textOrNull(row, "email"),
                        optionalUuid(row, "admittedBy"),
                        parseNullableInstant(row, "createdAt"),
                        parseNullableInstant(row, "expiresAt")));
            }
        }
        return out;
    }

    private WaitlistConfigView parseWaitlistConfig(JsonNode json) {
        return new WaitlistConfigView(
                json.path("enabled").asBoolean(false),
                json.path("weeklyApprovalLimit").asInt(0),
                json.path("admittedThisWeek").asInt(0));
    }

    @Override
    public DownstreamResult flagDeveloper(AdminCommand command) {
        Object value = command.params().getOrDefault("value", Boolean.TRUE);
        return postLegacy(downstream.entitlementsUrl() + "/internal/admin/flag_developer", ENTITLEMENTS_AUD,
                statusChangeBody(command, value));
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS, fallbackMethod = "listRefundRequestsFallback")
    public RefundRequestQueueView listRefundRequests(String status, int limit) {
        String url = downstream.billingUrl() + "/internal/refund-requests?status="
                + URLEncoder.encode(status, StandardCharsets.UTF_8) + "&limit=" + limit;
        try {
            JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
            List<RefundRequestQueueEntry> rows = new ArrayList<>();
            for (JsonNode n : json) {
                rows.add(new RefundRequestQueueEntry(
                        UUID.fromString(n.path("id").asText()),
                        n.path("ownerType").asText(),
                        UUID.fromString(n.path("ownerId").asText()),
                        n.path("status").asText(),
                        n.hasNonNull("requestedAmountCents")
                                ? n.path("requestedAmountCents").asLong() : null,
                        n.path("reason").asText(""),
                        n.path("snapshotRefundableCents").asLong(0L),
                        n.path("liveRefundableCents").asLong(0L),
                        n.hasNonNull("approvedAmountCents")
                                ? n.path("approvedAmountCents").asLong() : null,
                        n.hasNonNull("refundedCents") ? n.path("refundedCents").asLong() : null,
                        n.hasNonNull("residualCents") ? n.path("residualCents").asLong() : null,
                        n.path("resolutionNote").asText(null),
                        parseInstant(n.path("createdAt").asText(null)),
                        parseInstant(n.path("updatedAt").asText(null)),
                        null,
                        null));
            }
            return enrichIdentities(new RefundRequestQueueView(false, false, status, rows));
        } catch (DownstreamCallException e) {
            log.warn("refund-request queue unavailable: {}", e.getMessage());
            return new RefundRequestQueueView(true, false, status, List.of());
        }
    }

    /** Open-breaker / unexpected failure: same envelope as a billing outage, never a 500. */
    public RefundRequestQueueView listRefundRequestsFallback(String status, int limit, Throwable t) {
        log.warn("refund-request queue short-circuited: {}", t.toString());
        return new RefundRequestQueueView(true, false, status, List.of());
    }

    private static Instant parseInstant(String raw) {
        return raw == null || raw.isBlank() ? null : Instant.parse(raw);
    }

    /**
     * Attaches author names / emails without N+1'ing billing. A failure here is
     * {@code identityDegraded}, never {@code degraded} — the money figures are still trustworthy.
     */
    private RefundRequestQueueView enrichIdentities(RefundRequestQueueView view) {
        if (view.degraded() || view.requests().isEmpty()) {
            return view;
        }
        List<UUID> userIds = view.requests().stream()
                .filter(r -> "USER".equals(r.ownerType()))
                .map(RefundRequestQueueEntry::ownerId)
                .distinct()
                .toList();
        List<UUID> orgIds = view.requests().stream()
                .filter(r -> "ORG".equals(r.ownerType()))
                .map(RefundRequestQueueEntry::ownerId)
                .distinct()
                .toList();

        java.util.concurrent.atomic.AtomicBoolean identityDegraded =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Map<UUID, JsonNode> users = Map.of();
        Map<UUID, JsonNode> orgs = new java.util.concurrent.ConcurrentHashMap<>();

        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var usersF = userIds.isEmpty()
                    ? java.util.concurrent.CompletableFuture.completedFuture(Map.<UUID, JsonNode>of())
                    : java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> fetchUserIdentities(userIds), exec);
            List<java.util.concurrent.CompletableFuture<Void>> orgFs = orgIds.stream()
                    .map(id -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            JsonNode n = get(downstream.orgUrl() + "/internal/orgs/" + id + "/identity",
                                    ORG_AUD, IDENTITY_RPC_TIMEOUT);
                            orgs.put(id, n);
                        } catch (DownstreamCallException e) {
                            if (!isNotFound(e)) {
                                throw e;
                            }
                        }
                    }, exec))
                    .toList();
            try {
                users = usersF.get(IDENTITY_RPC_TIMEOUT.toMillis() + 250,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                identityDegraded.set(true);
                log.warn("refund-queue user identity unavailable: {}", e.toString());
            }
            if (!orgFs.isEmpty()) {
                try {
                    java.util.concurrent.CompletableFuture
                            .allOf(orgFs.toArray(java.util.concurrent.CompletableFuture[]::new))
                            .get(IDENTITY_RPC_TIMEOUT.toMillis() + 250,
                                    java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    identityDegraded.set(true);
                    log.warn("refund-queue org identity unavailable: {}", e.toString());
                }
            }
        }

        List<RefundRequestQueueEntry> enriched = new ArrayList<>();
        for (RefundRequestQueueEntry row : view.requests()) {
            String ownerName = null;
            String ownerEmail = null;
            if ("USER".equals(row.ownerType())) {
                JsonNode n = users.get(row.ownerId());
                if (n != null) {
                    ownerName = textOrNull(n, "authorName");
                    ownerEmail = textOrNull(n, "email");
                }
            } else if ("ORG".equals(row.ownerType())) {
                JsonNode n = orgs.get(row.ownerId());
                if (n != null) {
                    String author = textOrNull(n, "authorName");
                    ownerName = (author == null || author.isBlank())
                            ? textOrNull(n, "name") : author;
                }
            }
            enriched.add(new RefundRequestQueueEntry(
                    row.id(), row.ownerType(), row.ownerId(), row.status(),
                    row.requestedAmountCents(), row.reason(),
                    row.snapshotRefundableCents(), row.liveRefundableCents(),
                    row.approvedAmountCents(), row.refundedCents(), row.residualCents(),
                    row.resolutionNote(), row.createdAt(), row.updatedAt(),
                    ownerName, ownerEmail));
        }
        return new RefundRequestQueueView(false, identityDegraded.get(), view.status(), enriched);
    }

    private Map<UUID, JsonNode> fetchUserIdentities(List<UUID> userIds) {
        String url = downstream.entitlementsUrl() + "/internal/admin/users/by-ids";
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJsonValue(userIds), IDENTITY_RPC_TIMEOUT);
        Map<UUID, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode n : json) {
            if (n.hasNonNull("userId")) {
                out.put(UUID.fromString(n.path("userId").asText()), n);
            }
        }
        return out;
    }

    private static boolean isNotFound(DownstreamCallException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("HTTP 404");
    }

    @Override
    @CircuitBreaker(name = CB_BILLING, fallbackMethod = "resolveRefundRequestFallback")
    public RefundRequestResolveResult resolveRefundRequest(AdminCommand command) {
        // The ADMIN's figure is authoritative; billing clamps it to live refundable so a typo can
        // never return bonus credit — or money never paid — to a card.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", command.params().get("decision"));
        body.put("amountCents", command.params().get("amountCents"));
        body.put("note", command.params().get("note"));
        body.put("staffUserId", command.staffUserId().toString());
        // One OTP challenge = one resolve operation, reused verbatim on a retry — the same
        // stable-key contract the refund path uses. A fresh UUID per call would defeat billing's
        // claim guard on a retry.
        body.put("stepUpChallengeId", challengeIdOf(command.stepUpToken()));
        String url = downstream.billingUrl() + "/internal/refund-requests/"
                + command.params().get("requestId") + "/resolve";
        try {
            JsonNode json = send(url, BILLING_AUD, toJson(body), REFUND_RESOLVE_TIMEOUT);
            return new RefundRequestResolveResult(
                    true,
                    json.path("status").asText(null),
                    json.hasNonNull("approvedAmountCents")
                            ? json.path("approvedAmountCents").asLong() : null,
                    json.path("refundedCents").asLong(0L),
                    json.path("residualCents").asLong(0L));
        } catch (DownstreamCallException e) {
            return RefundRequestResolveResult.failed();
        }
    }

    public RefundRequestResolveResult resolveRefundRequestFallback(AdminCommand command, Throwable t) {
        log.warn("resolveRefundRequest short-circuited: {}", t.toString());
        return RefundRequestResolveResult.failed();
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS, fallbackMethod = "listRefundResidualsFallback")
    public RefundResidualQueueView listRefundResiduals() {
        String url = downstream.billingUrl() + "/internal/refund-residuals";
        try {
            JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
            if (json == null || !json.isArray()) {
                log.warn("refund-residual queue: expected a JSON array");
                return new RefundResidualQueueView(true, List.of());
            }
            List<RefundResidualEntry> rows = new ArrayList<>();
            for (JsonNode n : json) {
                rows.add(new RefundResidualEntry(
                        UUID.fromString(n.path("id").asText()),
                        n.path("ownerType").asText(),
                        UUID.fromString(n.path("ownerId").asText()),
                        n.hasNonNull("paymentId") ? UUID.fromString(n.path("paymentId").asText()) : null,
                        providerPaymentRef(n),
                        n.path("amountCents").asLong(0L),
                        n.path("reason").asText(""),
                        parseInstant(n.path("createdAt").asText(null))));
            }
            return new RefundResidualQueueView(false, rows);
        } catch (DownstreamCallException e) {
            log.warn("refund-residual queue unavailable: {}", e.getMessage());
            return new RefundResidualQueueView(true, List.of());
        }
    }

    /** Billing sends {@code providerPaymentRef}. Older payloads used {@code paddleTransactionId}. */
    static String providerPaymentRef(JsonNode n) {
        JsonNode current = n.get("providerPaymentRef");
        if (current != null && current.isTextual() && !current.asText().isBlank()) {
            return current.asText();
        }
        JsonNode legacy = n.get("paddleTransactionId");
        if (legacy != null && legacy.isTextual() && !legacy.asText().isBlank()) {
            return legacy.asText();
        }
        return null;
    }

    public RefundResidualQueueView listRefundResidualsFallback(Throwable t) {
        log.warn("refund-residual queue short-circuited: {}", t.toString());
        return new RefundResidualQueueView(true, List.of());
    }

    @Override
    @CircuitBreaker(name = CB_BILLING, fallbackMethod = "settleRefundResidualFallback")
    public RefundResidualSettleResult settleRefundResidual(AdminCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("residualId", command.params().get("residualId"));
        body.put("staffUserId", command.staffUserId().toString());
        // Full token: settling a residual mints wallet credit, so billing gates it exactly like a refund.
        body.put("stepUpToken", command.stepUpToken());
        String url = downstream.billingUrl() + "/internal/refund-residuals/settle";
        try {
            send(url, BILLING_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
            return new RefundResidualSettleResult(true);
        } catch (DownstreamCallException e) {
            return RefundResidualSettleResult.failed();
        }
    }

    public RefundResidualSettleResult settleRefundResidualFallback(AdminCommand command, Throwable t) {
        log.warn("settleRefundResidual short-circuited: {}", t.toString());
        return RefundResidualSettleResult.failed();
    }

    @Override
    @CircuitBreaker(name = CB_BILLING, fallbackMethod = "refundFallback")
    public DownstreamResult refund(AdminCommand command) {
        // billing POST /internal/refund (D6 / P11). The body carries the FULL step-up token — billing
        // verifies and spends the approval itself rather than trusting a caller-named challenge id,
        // which any mesh caller could invent. Plus a per-operation idempotencyKey so a retry of THIS refund
        // never double-debits the wallet. The 200 body reports BOTH the clamped ask
        // (requestedCents) and what was actually pulled back (walletDebitedCents) so the admin sees
        // "asked X, refunded Y" (F1 / D). A non-2xx (or transport fault) maps to success=false so
        // the gated runWrite path surfaces the action as failed rather than leaking the exception.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", command.params().get("paymentId"));
        body.put("refundAmountCents", command.params().get("refundAmountCents"));
        body.put("stepUpToken", command.stepUpToken());
        body.put("staffUserId", command.staffUserId().toString());
        body.put("targetUserId", command.targetUserId().toString());
        // H2 fix — the idempotencyKey MUST be STABLE across retries of the SAME refund operation
        // (RefundRequest contract: "the saga reuses the original challenge id on retry"). The prior
        // code minted a FRESH UUID.randomUUID() per call, so a retry of one operation reached billing
        // as a brand-new key → billing missed its replay pre-check and DOUBLE-debited the wallet +
        // issued a SECOND Stripe refund (the over-refund H1 surfaced from billing's side). The
        // step-up challenge id is the contract-specified per-operation key: one OTP challenge = one
        // refund operation, reused verbatim on a retry, distinct for a freshly-authorised refund.
        body.put("idempotencyKey", challengeIdOf(command.stepUpToken()));
        Object namedRequest = command.params().get("refundRequestId");
        if (namedRequest != null && !namedRequest.toString().isBlank()) {
            body.put("refundRequestId", namedRequest.toString());
        }
        try {
            JsonNode json = send(downstream.billingUrl() + "/internal/refund", BILLING_AUD,
                    toJson(body), ADMIN_RPC_TIMEOUT);
            boolean success = json.path("success").asBoolean(false);
            UUID auditEntryId = success ? optionalUuid(json, "auditEntryId") : null;
            long requestedCents = json.path("requestedCents").asLong(0L);
            long walletDebitedCents = json.path("walletDebitedCents").asLong(0L);
            boolean idempotentReplay = json.path("idempotentReplay").asBoolean(false);
            return DownstreamResult.refund(success, auditEntryId, requestedCents,
                    walletDebitedCents, idempotentReplay);
        } catch (DownstreamConflictException conflict) {
            // 409: a refund for this payment is already awaiting Stripe's card-credit approval
            // (billing's PENDING_CARD_REFUND guard). NOT an outage and NOT a failed action to retry
            // now — log distinctly so ops don't chase it as a billing error. Reported as
            // success=false (the action did not occur); the operator retries once the prior settles.
            log.warn("Refund for payment {} rejected as a conflict (409): a prior refund is still "
                    + "awaiting card credit — retry after it settles", command.params().get("paymentId"));
            return new DownstreamResult(false, null, null);
        } catch (DownstreamCallException e) {
            return new DownstreamResult(false, null, null);
        }
    }

    public DownstreamResult refundFallback(AdminCommand command, Throwable t) {
        log.warn("refund short-circuited: {}", t.toString());
        return new DownstreamResult(false, null, null);
    }

    @Override
    @CircuitBreaker(name = CB_BILLING, fallbackMethod = "forceResolveReversedFallback")
    public ForceResolveReversedCallResult forceResolveReversed(AdminCommand command) {
        Object paymentId = command.params().get("paymentId");
        if (paymentId == null || paymentId.toString().isBlank()) {
            return ForceResolveReversedCallResult.failed();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stepUpToken", command.stepUpToken());
        body.put("staffUserId", command.staffUserId().toString());
        if (command.targetUserId() != null) {
            body.put("targetUserId", command.targetUserId().toString());
        }
        Object note = command.params().get("note");
        if (note != null && !note.toString().isBlank()) {
            body.put("note", note.toString());
        }
        String url = downstream.billingUrl() + "/internal/admin/payments/"
                + paymentId + "/force-resolve-reversed";
        try {
            JsonNode json = send(url, BILLING_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
            UUID id = optionalUuid(json, "paymentId");
            if (id == null) {
                return ForceResolveReversedCallResult.failed();
            }
            return ForceResolveReversedCallResult.ok(id, json.path("restoredCents").asLong(0L));
        } catch (DownstreamCallException e) {
            return ForceResolveReversedCallResult.failed();
        }
    }

    public ForceResolveReversedCallResult forceResolveReversedFallback(AdminCommand command, Throwable t) {
        log.warn("forceResolveReversed short-circuited: {}", t.toString());
        return ForceResolveReversedCallResult.failed();
    }

    @Override
    public AccountStatusView accountStatus(UUID userId) {
        String url = downstream.entitlementsUrl() + "/internal/admin/users/" + userId + "/status";
        JsonNode json = get(url, ENTITLEMENTS_AUD, STEP_UP_RPC_TIMEOUT);
        String status = json.path("accountStatus").asText(null);
        return new AccountStatusView(userId, status);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public OtpChallengeResult otpStart(UUID staffUserId, String staffEmail, String otpAction,
                                       Map<String, Object> actionParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", staffUserId.toString());
        if (staffEmail != null && !staffEmail.isBlank()) {
            body.put("email", staffEmail);  // D17: delivery override for non-entitlements actors
        }
        body.put("action", otpAction);
        body.put("actionParams", actionParams);
        String url = downstream.entitlementsUrl() + "/internal/otp/start";
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        UUID challengeId = optionalUuid(json, "challengeId");
        String expiresAtRaw = textOrNull(json, "expiresAt");
        Instant expiresAt = expiresAtRaw == null ? null : Instant.parse(expiresAtRaw);
        String hint = textOrNull(json, "deliveryTargetHint");
        return new OtpChallengeResult(challengeId, expiresAt, hint);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public OtpCodeVerifyResult otpVerify(UUID challengeId, String code, String otpAction,
                                         Map<String, Object> actionParams) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId.toString());
        body.put("code", code);
        body.put("action", otpAction);
        body.put("actionParams", actionParams);
        String url = downstream.entitlementsUrl() + "/internal/otp/verify";
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        boolean verified = json.path("verified").asBoolean(false);
        String failureReason = textOrNull(json, "failureReason");
        return new OtpCodeVerifyResult(verified, failureReason);
    }

    @Override
    public OrgView org(UUID orgId) {
        String url = downstream.orgUrl() + "/internal/orgs/" + orgId;
        JsonNode json = get(url, ORG_AUD, STEP_UP_RPC_TIMEOUT);
        return new OrgView(
                orgId,
                json.path("name").asText(null),
                optionalUuid(json, "walletId"),
                json.path("maxAdmins").asInt(0),
                textOrNull(json, "domainLockDomain"),
                optionalUuid(json, "createdBy"));
    }

    // ───────── Phase 3b admin-portal endpoints ─────────

    @Override
    @CircuitBreaker(name = CB_BILLING)
    public WalletBalanceView walletTopUp(UUID userId, long amountCents, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerType", "USER");
        body.put("ownerId", userId.toString());
        body.put("amountCents", amountCents);
        // billing's CreditRequest.reason is the WalletTxnReason ENUM, but the caller's `reason`
        // is the operator's free-text audit note (WalletTopUpRequest: "free-form audit reason").
        // Forwarding it verbatim made every top-up whose note wasn't an exact enum name 400 at
        // billing (the SPA's own 'admin top-up' string included), and an operator typing
        // "REFUND" would have poisoned the spend report's sign-based refund classification.
        // The ledger reason for an admin top-up is ALWAYS ADMIN_CREDIT; the free text lives in
        // the audit log, not the money ledger.
        body.put("reason", "ADMIN_CREDIT");
        String url = downstream.billingUrl() + "/internal/wallet/credit";
        JsonNode json = send(url, BILLING_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        long balance = json.path("balanceCents").asLong(0L);
        return new WalletBalanceView(userId, balance);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public PlatformAdminUpdateView setIsPlatformAdmin(UUID userId, boolean isPlatformAdmin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isPlatformAdmin", isPlatformAdmin);
        String url = downstream.entitlementsUrl() + "/internal/admin/users/" + userId;
        JsonNode json = sendPatch(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        boolean persisted = json.path("isPlatformAdmin").asBoolean(isPlatformAdmin);
        return new PlatformAdminUpdateView(userId, persisted);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public List<UserSearchResultEntry> searchUsers(String emailQuery, int limit) {
        String encoded = URLEncoder.encode(emailQuery == null ? "" : emailQuery, StandardCharsets.UTF_8);
        String url = downstream.entitlementsUrl() + "/internal/admin/users/search?email="
                + encoded + "&limit=" + Math.max(1, limit);
        JsonNode json = get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT);
        List<UserSearchResultEntry> out = new ArrayList<>();
        JsonNode results = json.path("results").isArray() ? json.path("results") : json;
        if (results.isArray()) {
            for (JsonNode row : results) {
                UUID userId = optionalUuid(row, "userId");
                if (userId == null) {
                    continue;
                }
                String email = textOrNull(row, "email");
                String accountStatus = textOrNull(row, "accountStatus");
                boolean admin = row.path("isPlatformAdmin").asBoolean(false);
                String createdAtRaw = textOrNull(row, "createdAt");
                Instant createdAt = createdAtRaw == null ? null : Instant.parse(createdAtRaw);
                out.add(new UserSearchResultEntry(userId, email, accountStatus, admin, createdAt));
            }
        }
        return out;
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public OrgPricingRuleView upsertOrgPricing(UUID orgId, String action, String category,
                                               long valueCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgId", orgId.toString());
        body.put("action", action);
        body.put("valueCents", valueCents);
        if (category != null && !category.isBlank()) {
            body.put("category", category);
        }
        return postPricingOverride("/internal/pricing/org-override", orgId, action, category,
                valueCents, body);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public OrgPricingRuleView upsertGlobalPricing(String action, String category, long valueCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("valueCents", valueCents);
        if (category != null && !category.isBlank()) {
            body.put("category", category);
        }
        return postPricingOverride("/internal/pricing/global-override", null, action, category,
                valueCents, body);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public Map<String, Long> currentPricing() {
        String url = downstream.entitlementsUrl() + "/internal/pricing/active-by-action";
        JsonNode json = get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT);
        Map<String, Long> out = new LinkedHashMap<>();
        json.fields().forEachRemaining(e -> {
            // Omit a malformed/non-numeric value rather than coercing it to 0 — a
            // $0.00 price reads as "free"; the SPA shows the action as "—" instead.
            if (e.getValue().isNumber()) {
                out.put(e.getKey(), e.getValue().asLong());
            }
        });
        return out;
    }

    private OrgPricingRuleView postPricingOverride(String path, UUID orgId, String action,
                                                   String category, long valueCents,
                                                   Map<String, Object> body) {
        String url = downstream.entitlementsUrl() + path;
        JsonNode json = send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
        long persistedValue = json.path("valueCents").asLong(valueCents);
        Long previous = json.has("previousValueCents") && !json.path("previousValueCents").isNull()
                ? json.path("previousValueCents").asLong() : null;
        String updatedAtRaw = textOrNull(json, "updatedAt");
        Instant updatedAt = updatedAtRaw == null ? Instant.now() : Instant.parse(updatedAtRaw);
        String persistedCategory = textOrNull(json, "category");
        if (persistedCategory == null) {
            persistedCategory = category;
        }
        return new OrgPricingRuleView(orgId, action, persistedCategory, persistedValue, previous,
                updatedAt);
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS)
    public void writeAuditLog(AuditLogEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorUserId", entry.actorUserId() == null ? null : entry.actorUserId().toString());
        body.put("action", entry.action());
        body.put("resourceType", entry.resourceType());
        body.put("resourceId", entry.resourceId());
        body.put("oldValue", entry.oldValue());
        body.put("newValue", entry.newValue());
        body.put("decision", entry.decision());
        body.put("ip", entry.ip());
        String url = downstream.entitlementsUrl() + "/internal/audit-log";
        send(url, ENTITLEMENTS_AUD, toJson(body), ADMIN_RPC_TIMEOUT);
    }

    // ───────── Spend dashboard reads ─────────
    //
    // These use the DEDICATED report breakers (CB_*_REPORTS): the dashboard's aggregate
    // reads must never be able to open the breakers gating the money-path admin ops.
    // Money fields are parsed STRICTLY (requireLong): a missing/renamed cents field is a
    // contract break and must fail loud, not render as a plausible-looking $0.00.

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS_REPORTS)
    public SpendReportView ownerSpend(String ownerType, UUID ownerId) {
        String url = downstream.entitlementsUrl() + "/internal/admin/spend?ownerType="
                + ownerType + "&ownerId=" + ownerId;
        return parseSpendReport(get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT));
    }

    @Override
    @CircuitBreaker(name = CB_ENTITLEMENTS_REPORTS)
    public SpendReportView globalSpend() {
        String url = downstream.entitlementsUrl() + "/internal/admin/spend/global";
        return parseSpendReport(get(url, ENTITLEMENTS_AUD, ADMIN_RPC_TIMEOUT));
    }

    /**
     * Per-book GROSS charges. Fail-soft on any transport/HTTP/parse failure so a slow
     * entitlements never 502s the Analytics page — an empty list with {@code degraded=true}
     * is returned (zeros render, and the UI shows a "lookup failed" warning so degraded
     * entitlements is distinguishable from "no charges"). Bucket cents are {@code requireLong}:
     * entitlements, admin-api, and admin-spa ship together; a lump-only payload is a
     * contract break, not a silent $0 split. No circuit breaker: a dashboard miss must
     * not open the entitlements-reports breaker used by spend tiles.
     */
    @Override
    public SpendByBooksResponse chargesByBooks(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return new SpendByBooksResponse(false, List.of());
        }
        try {
            String url = downstream.entitlementsUrl() + "/internal/admin/spend/by-books";
            String body = objectMapper.writeValueAsString(bookIds);
            JsonNode json = send(url, ENTITLEMENTS_AUD, body, BY_BOOKS_RPC_TIMEOUT);
            List<SpendByBookChargeView> out = new ArrayList<>();
            if (json == null || !json.isArray()) {
                return new SpendByBooksResponse(false, out);
            }
            for (JsonNode row : json) {
                String bookId = textOrNull(row, "bookId");
                if (bookId == null) {
                    continue;
                }
                out.add(new SpendByBookChargeView(
                        bookId,
                        requireLong(row, "chargedCents", "entitlements spend by-books"),
                        requireLong(row, "chargeCount", "entitlements spend by-books"),
                        requireLong(row, "generationCents", "entitlements spend by-books"),
                        requireLong(row, "editCents", "entitlements spend by-books"),
                        requireLong(row, "extendCents", "entitlements spend by-books"),
                        requireLong(row, "publishCents", "entitlements spend by-books"),
                        requireLong(row, "otherCents", "entitlements spend by-books")));
            }
            return new SpendByBooksResponse(false, out);
        } catch (Exception e) {
            log.warn("entitlements spend/by-books fail-soft: returning degraded empty charges bookCount={} reason={}",
                    bookIds.size(), e.toString());
            return new SpendByBooksResponse(true, List.of());
        }
    }

    private SpendReportView parseSpendReport(JsonNode json) {
        List<SpendActionEntry> byAction = new ArrayList<>();
        for (JsonNode row : json.path("byAction")) {
            byAction.add(new SpendActionEntry(
                    textOrNull(row, "action"),
                    requireLong(row, "spentCents", "entitlements spend byAction"),
                    requireLong(row, "chargeCount", "entitlements spend byAction")));
        }
        return new SpendReportView(
                requireLong(json, "grossSpentCents", "entitlements spend"),
                requireLong(json, "walletChargeCount", "entitlements spend"),
                byAction);
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS)
    public WalletSummaryView walletSummary(String ownerType, UUID ownerId) {
        String url = downstream.billingUrl() + "/internal/admin/wallet/summary?ownerType="
                + ownerType + "&ownerId=" + ownerId;
        JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
        String source = "billing wallet summary";
        return new WalletSummaryView(
                json.path("walletExists").asBoolean(false),
                requireLong(json, "balanceCents", source),
                requireLong(json, "moneyInCents", source),
                requireLong(json, "consumeRefundCents", source),
                requireLong(json, "paymentClawbackCents", source),
                requireLong(json, "stripeCashGrossCents", source),
                requireLong(json, "stripeCardRefundedCents", source));
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS)
    public GlobalWalletSummaryView walletSummaryGlobal() {
        String url = downstream.billingUrl() + "/internal/admin/wallet/summary/global";
        JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
        String source = "billing global wallet summary";
        return new GlobalWalletSummaryView(
                requireLong(json, "totalBalanceCents", source),
                requireLong(json, "userBalanceCents", source),
                requireLong(json, "orgBalanceCents", source),
                requireLong(json, "moneyInCents", source),
                requireLong(json, "consumeRefundCents", source),
                requireLong(json, "paymentClawbackCents", source),
                requireLong(json, "stripeCashGrossCents", source),
                requireLong(json, "stripeCardRefundedCents", source));
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS)
    public MoneyFlowsView walletFlows() {
        String url = downstream.billingUrl() + "/internal/admin/wallet/flows";
        JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
        String source = "billing wallet flows";
        return new MoneyFlowsView(
                requireLong(json, "weekLoadedCents", source),
                requireLong(json, "weekSpentCents", source),
                requireLong(json, "monthLoadedCents", source),
                requireLong(json, "monthSpentCents", source),
                requireLong(json, "yearLoadedCents", source),
                requireLong(json, "yearSpentCents", source),
                requireLong(json, "allTimeLoadedCents", source),
                requireLong(json, "allTimeSpentCents", source));
    }

    @Override
    @CircuitBreaker(name = CB_BILLING_REPORTS)
    public PaymentHealthView paymentsHealth() {
        String url = downstream.billingUrl() + "/internal/admin/payments/health";
        JsonNode json = get(url, BILLING_AUD, ADMIN_RPC_TIMEOUT);
        String source = "billing payments health";
        return new PaymentHealthView(
                requireLong(json, "stuckRefundCount", source),
                requireLong(json, "stuckTopupCount", source),
                requireLong(json, "abandonedTopupCount", source),
                parseNullableInstant(json, "lastCompletedWebhookAt"),
                requireLong(json, "completedWebhooks24h", source),
                requireLong(json, "stuckRefundThresholdMinutes", source),
                requireLong(json, "stuckTopupThresholdMinutes", source));
    }

    /**
     * Strict accessor for money fields: a missing or non-numeric cents field in a 2xx body is
     * a wire-contract break — surfacing it as $0.00 on an exact-cents dashboard would be a
     * silently wrong number, so fail loud instead.
     */
    private long requireLong(JsonNode json, String field, String source) {
        JsonNode node = json.path(field);
        if (!node.isNumber()) {
            throw new DownstreamCallException(
                    "Missing/non-numeric field '" + field + "' in " + source + " response", null);
        }
        return node.asLong();
    }

    /** Nullable Instant — never route through requireLong (timestamps are ISO-8601 strings). */
    private static Instant parseNullableInstant(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return Instant.parse(node.asText());
    }

    // ───────── Internals ─────────

    private Map<String, Object> statusChangeBody(AdminCommand command, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", command.targetUserId().toString());
        body.put("staffUserId", command.staffUserId().toString());
        body.put("stepUpChallengeId", challengeIdOf(command.stepUpToken()));
        if (value != null) {
            body.put("value", value);
        }
        return body;
    }

    /**
     * B4 fix — the downstream {@code stepUpChallengeId} is a {@code @NotNull UUID}; the admin-api
     * step-up token's first pipe-delimited segment IS that challenge id
     * ({@code challengeId|action|paramsHash|expiresAt|signature}). Forwarding the FULL token string
     * (as the prior code did) made Jackson reject it as a malformed UUID (400). We extract the
     * challenge-id segment with {@code split('|',2)[0]}. The token is admin-api's OWN gate; the
     * downstream callees do not re-verify it, so the challenge id is all they need for their own
     * single-use ledger. Returns {@code null} for a null/blank token (the gate already rejected).
     */
    private String challengeIdOf(String stepUpToken) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            return null;
        }
        return stepUpToken.split("\\|", 2)[0];
    }

    /**
     * Legacy post used by the Phase-3a step-up-gated writes. Translates downstream non-2xx
     * (or transport failure) into a {@code success=false} result so {@code AdminService.runWrite}
     * surfaces the action as failed without leaking the exception up to the controller — see the
     * "endpoint returns HTTP 500" Cucumber scenario.
     */
    private DownstreamResult postLegacy(String url, String audience, Map<String, Object> body) {
        try {
            JsonNode json = send(url, audience, toJson(body), STEP_UP_RPC_TIMEOUT);
            boolean success = json.path("success").asBoolean(false);
            UUID auditEntryId = success ? optionalUuid(json, "auditEntryId") : null;
            String accountStatus = json.path("accountStatus").asText(null);
            return new DownstreamResult(success, auditEntryId, accountStatus);
        } catch (DownstreamCallException e) {
            return new DownstreamResult(false, null, null);
        }
    }

    private JsonNode get(String url, String audience, Duration timeout) {
        HttpRequest request = baseRequest(url, audience, timeout).GET().build();
        return execute(request);
    }

    private JsonNode send(String url, String audience, String jsonBody, Duration timeout) {
        HttpRequest request = baseRequest(url, audience, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    private JsonNode sendPatch(String url, String audience, String jsonBody, Duration timeout) {
        HttpRequest request = baseRequest(url, audience, timeout)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    private HttpRequest.Builder baseRequest(String url, String audience, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + tokenFactory.mint(audience));
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                // Business-state conflict (e.g. billing: a refund already awaiting card credit), not
                // an outage — distinct type so the refund path can report it as "in progress".
                throw new DownstreamConflictException("Downstream " + request.uri() + " returned HTTP 409");
            }
            if (response.statusCode() >= 400) {
                throw new DownstreamCallException("Downstream " + request.uri() + " returned HTTP "
                        + response.statusCode(), null);
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(body);
        } catch (DownstreamCallException e) {
            throw e;
        } catch (Exception e) {
            throw new DownstreamCallException("Downstream call failed: " + request.uri(), e);
        }
    }

    private String toJson(Map<String, Object> body) {
        return toJsonValue(body);
    }

    private String toJsonValue(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new DownstreamCallException("Failed to serialise request body", e);
        }
    }

    private UUID optionalUuid(JsonNode json, String field) {
        String raw = textOrNull(json, field);
        return raw == null ? null : UUID.fromString(raw);
    }

    /** Returns the field's text, or null when the field is absent or a JSON {@code null}. */
    private String textOrNull(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.asText(null);
        return (raw == null || raw.isBlank()) ? null : raw;
    }
}
