package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Per-book GROSS charges for the Analytics COGS-vs-charges table, wrapping the
 * rows with an explicit {@code degraded} flag.
 *
 * <p>Without this flag an empty {@code charges} list is ambiguous: it can mean
 * "these books genuinely had no wallet charges" OR "the entitlements lookup
 * fail-softed". The UI must render a warning only in the latter case, so the
 * degradation has to be signalled distinctly rather than inferred from emptiness.
 */
public record SpendByBooksResponse(
        boolean degraded,
        List<SpendByBookChargeView> charges
) {}
