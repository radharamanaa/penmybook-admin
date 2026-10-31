package dev.bookforge.adminapi.service;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.dto.MoneyAlertEntry;
import dev.bookforge.adminapi.dto.MoneyAlertQueueView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Composes the staff Money alerts list from billing's two alert reads (open, and closed).
 *
 * <p>Billing pages each read by a limit only, with no severity filter, so this reads billing's
 * full page ({@link #BILLING_PAGE}) and filters and trims here. "All" is the open page plus the
 * closed page, newest raised first. Either read failing marks the whole list degraded.
 */
@Service
public class MoneyAlertService {

    /** Billing's ceiling for one page of alerts ({@code AdminAlertService} caps at 200). */
    public static final int BILLING_PAGE = 200;

    public static final String FILTER_OPEN = "OPEN";
    public static final String FILTER_ALL = "ALL";
    private static final String BILLING_STATUS_OPEN = "OPEN";
    private static final String BILLING_STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";

    private static final Comparator<MoneyAlertEntry> NEWEST_RAISED_FIRST = Comparator.comparing(
            MoneyAlertEntry::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));

    private final DownstreamClient downstream;

    public MoneyAlertService(DownstreamClient downstream) {
        this.downstream = downstream;
    }

    /**
     * @param filter   {@link #FILTER_OPEN} or {@link #FILTER_ALL} (validated by the caller)
     * @param severity a billing severity to keep, or null for every severity
     * @param limit    how many rows to return at most
     */
    public MoneyAlertQueueView list(String filter, String severity, int limit) {
        MoneyAlertQueueView open = downstream.listMoneyAlerts(BILLING_STATUS_OPEN, BILLING_PAGE);
        List<MoneyAlertEntry> rows = new ArrayList<>(open.alerts());
        boolean degraded = open.degraded();
        if (FILTER_ALL.equals(filter)) {
            MoneyAlertQueueView closed =
                    downstream.listMoneyAlerts(BILLING_STATUS_ACKNOWLEDGED, BILLING_PAGE);
            rows.addAll(closed.alerts());
            degraded = degraded || closed.degraded();
        }
        Stream<MoneyAlertEntry> kept = rows.stream()
                .filter(alert -> severity == null || severity.equals(alert.severity()));
        if (FILTER_ALL.equals(filter)) {
            kept = kept.sorted(NEWEST_RAISED_FIRST);
        }
        List<MoneyAlertEntry> shown = kept.limit(limit).toList();
        // A full page of other severities says nothing about older alerts of this one.
        boolean filteredAPartialPage = severity != null && shown.isEmpty()
                && open.alerts().size() >= BILLING_PAGE;
        return new MoneyAlertQueueView(degraded || filteredAPartialPage, shown);
    }
}
