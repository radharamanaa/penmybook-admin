package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Staff residual queue envelope. {@code degraded} means billing did not answer — never treat
 * an empty {@code residuals} list as "queue is clear" when this is true.
 */
public record RefundResidualQueueView(boolean degraded, List<RefundResidualEntry> residuals) {}
