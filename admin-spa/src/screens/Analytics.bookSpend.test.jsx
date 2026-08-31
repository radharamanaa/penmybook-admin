import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('../api.js', () => ({
  api: {
    analyticsConversations: vi.fn(),
    analyticsBooks: vi.fn(),
    analyticsLive: vi.fn(),
    analyticsBooksList: vi.fn(),
    analyticsGenreCounts: vi.fn(),
    spendByBooks: vi.fn(),
  },
}))

import Analytics from './Analytics.jsx'
import { api } from '../api.js'

describe('Analytics per-book customer spend (S1)', () => {
  it('shows generation / edit / extend / publish wallet buckets', async () => {
    api.analyticsConversations.mockResolvedValue({
      conversation_count: 0, converted_to_book: 0, avg_cost_usd: 0, total_cost_usd: 0,
    })
    api.analyticsBooks.mockResolvedValue({
      books_generated: 0, avg_total_tokens: 0, avg_images_per_book: 0, avg_cost_usd: 0, total_cost_usd: 0,
    })
    api.analyticsLive.mockResolvedValue({
      books_planning: 0, books_generating: 0, books_chapters_complete: 0, books_publishing: 0, total_in_flight: 0,
    })
    api.analyticsGenreCounts.mockResolvedValue({
      n: 8, legacy_finished_unreliable: 0,
      today: { started: {}, finished: {} },
      this_week: { started: {}, finished: {} },
      weeks: [],
    })
    api.analyticsBooksList.mockResolvedValue({
      total: 1,
      books: [{
        book_id: 'book-1',
        book_title: 'Alpha',
        status: 'completed',
        created_at: '2026-08-30T12:00:00Z',
        estimated_cost_usd: 1.5,
      }],
    })
    api.spendByBooks.mockResolvedValue({
      degraded: false,
      charges: [{
        bookId: 'book-1',
        chargedCents: 900,
        chargeCount: 7,
        generationCents: 500,
        editCents: 75,
        extendCents: 250,
        publishCents: 50,
        otherCents: 25,
      }],
    })

    render(<Analytics />)

    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('Customer: generate')).toBeInTheDocument()
    expect(screen.getByText('Customer: edit')).toBeInTheDocument()
    expect(screen.getByText('Customer: extend')).toBeInTheDocument()
    expect(screen.getByText('Customer: publish')).toBeInTheDocument()
    expect(screen.getByText('Customer: other')).toBeInTheDocument()
    expect(screen.getByText('Customer total')).toBeInTheDocument()
    expect(screen.getByText('$5.00')).toBeInTheDocument()
    expect(screen.getByText('$2.50')).toBeInTheDocument()
    expect(screen.getByText('$0.75')).toBeInTheDocument()
    expect(screen.getByText('$0.50')).toBeInTheDocument()
    expect(screen.getByText('$0.25')).toBeInTheDocument()
    expect(screen.getByText('$9.00')).toBeInTheDocument()
    expect(screen.getByText('$1.50')).toBeInTheDocument()
    expect(screen.getByText('$7.50')).toBeInTheDocument()
  })
})
