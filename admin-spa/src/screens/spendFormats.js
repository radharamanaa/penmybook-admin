/**
 * Admin Spend: unique books per bind profile vs customer WALLET $ for that
 * PUBLISH_* action. Book counts come from curator usage rollup
 * (available_profiles); cents from entitlements byAction.
 */
export const PUBLISH_FORMATS = [
  { label: 'Digital PDF', booksField: 'books_digital', action: 'PUBLISH_DIGITAL' },
  { label: 'Paperback (KDP)', booksField: 'books_printed', action: 'PUBLISH_PAPERBACK' },
  { label: 'Kindle', booksField: 'books_kindle', action: 'PUBLISH_KINDLE' },
  { label: 'EPUB', booksField: 'books_epub', action: 'PUBLISH_EPUB' },
]

export function addUsageCounts(a, b) {
  const out = {}
  for (const f of PUBLISH_FORMATS) {
    const k = f.booksField
    out[k] = (a?.[k] ?? 0) + (b?.[k] ?? 0)
  }
  return out
}

export function usageHasFormatCounts(usage) {
  if (!usage || typeof usage !== 'object') return false
  return PUBLISH_FORMATS.every((f) => typeof usage[f.booksField] === 'number')
}

export function lanesHaveFormatCounts(lanes) {
  return Boolean(
    lanes
    && usageHasFormatCounts(lanes.individual)
    && usageHasFormatCounts(lanes.org),
  )
}

/**
 * Null when both sides of the join are present. Otherwise a warning that
 * explains why the table is hidden (never show live $ against silent 0 books).
 */
export function formatJoinWarning({
  moneyLoaded,
  countsReady,
  staleCounts = false,
  moneyError,
  usageError,
}) {
  if (countsReady && moneyLoaded) return null
  const parts = []
  if (staleCounts) {
    parts.push(
      'curator did not return per-format book counts (books_digital, books_kindle, books_epub). Roll curator with this admin SPA',
    )
  } else if (!countsReady) {
    parts.push(
      usageError ? `book counts failed (${usageError})` : 'book counts did not load',
    )
  }
  if (!moneyLoaded) {
    parts.push(
      moneyError ? `wallet spend failed (${moneyError})` : 'wallet spend did not load',
    )
  }
  return (
    `Publish-by-format table hidden: ${parts.join('; ')}. `
    + 'Customer $ is not shown against unknown book counts; wallet spend still appears under Spend by action when it loaded.'
  )
}

export function formatSpendRows(usage, byAction) {
  const by = new Map()
  for (const row of byAction ?? []) {
    if (row?.action) by.set(row.action, row)
  }
  return PUBLISH_FORMATS.map((f) => {
    const money = by.get(f.action) ?? {}
    return {
      label: f.label,
      books: usage?.[f.booksField] ?? 0,
      spentCents: money.spentCents ?? 0,
      chargeCount: money.chargeCount ?? 0,
    }
  })
}
