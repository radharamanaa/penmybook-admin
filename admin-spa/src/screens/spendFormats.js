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
