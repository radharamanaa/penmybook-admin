import { describe, it, expect } from 'vitest'
import { addUsageCounts, formatSpendRows } from './spendFormats.js'

describe('publish format spend join (S2)', () => {
  it('joins unique-book counts to PUBLISH_* wallet cents', () => {
    const usage = {
      books_digital: 3,
      books_printed: 1,
      books_kindle: 2,
      books_epub: 0,
    }
    const byAction = [
      { action: 'PUBLISH_DIGITAL', spentCents: 300, chargeCount: 3 },
      { action: 'PUBLISH_PAPERBACK', spentCents: 1500, chargeCount: 1 },
      { action: 'PUBLISH_KINDLE', spentCents: 200, chargeCount: 2 },
    ]
    const rows = formatSpendRows(usage, byAction)
    expect(rows).toEqual([
      { label: 'Digital PDF', books: 3, spentCents: 300, chargeCount: 3 },
      { label: 'Paperback (KDP)', books: 1, spentCents: 1500, chargeCount: 1 },
      { label: 'Kindle', books: 2, spentCents: 200, chargeCount: 2 },
      { label: 'EPUB', books: 0, spentCents: 0, chargeCount: 0 },
    ])
  })

  it('sums lane usage for the platform table', () => {
    expect(addUsageCounts(
      { books_digital: 2, books_printed: 1 },
      { books_digital: 5, books_kindle: 1 },
    )).toEqual({
      books_digital: 7,
      books_printed: 1,
      books_kindle: 1,
      books_epub: 0,
    })
  })
})
