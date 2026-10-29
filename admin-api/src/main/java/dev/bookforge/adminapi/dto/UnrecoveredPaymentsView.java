package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Staff "money not recovered" envelope. {@code degraded} means billing did not answer — never
 * treat an empty {@code payments} list as "nothing unrecovered" when this is true.
 */
public record UnrecoveredPaymentsView(boolean degraded, List<UnrecoveredPaymentEntry> payments) {}
