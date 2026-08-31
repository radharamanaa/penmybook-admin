import { describe, it, expect } from 'vitest'
import {
  addUsageCounts,
  formatJoinWarning,
  formatSpendRows,
  lanesHaveFormatCounts,
  usageHasFormatCounts,
} from './spendFormats.js'

const FORMAT_USAGE = {
  books_digital: 0,
  books_printed: 1,
  books_kindle: 0,
  books_epub: 0,
}

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

  it('treats missing format keys as not ready (old curator)', () => {
    expect(usageHasFormatCounts({ books_printed: 1 })).toBe(false)
    expect(usageHasFormatCounts(FORMAT_USAGE)).toBe(true)
    expect(lanesHaveFormatCounts({
      individual: FORMAT_USAGE,
      org: FORMAT_USAGE,
    })).toBe(true)
    expect(lanesHaveFormatCounts({
      individual: { books_printed: 1 },
      org: FORMAT_USAGE,
    })).toBe(false)
  })

  it('is silent when both sides of the join are present', () => {
    expect(formatJoinWarning({ moneyLoaded: true, countsReady: true })).toBeNull()
  })

  it('explains missing book counts and names the curator error', () => {
    const msg = formatJoinWarning({
      moneyLoaded: true,
      countsReady: false,
      usageError: 'usage lanes 504',
    })
    expect(msg).toMatch(/table hidden/i)
    expect(msg).toContain('usage lanes 504')
    expect(msg).not.toMatch(/wallet spend failed/)
  })

  it('explains a curator that omitted per-format fields', () => {
    const msg = formatJoinWarning({
      moneyLoaded: true,
      countsReady: false,
      staleCounts: true,
    })
    expect(msg).toContain('books_digital')
    expect(msg).toMatch(/Roll curator/)
  })
})
