package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Staff money-alert list envelope. {@code degraded} means billing did not answer: an empty
 * {@code alerts} list is what everyone hopes to see, so it must never be read as "nothing is
 * wrong" when this is true.
 */
public record MoneyAlertQueueView(boolean degraded, List<MoneyAlertEntry> alerts) {}
