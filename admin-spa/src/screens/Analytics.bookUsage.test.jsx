import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

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

const BOOK_WITH_USAGE = {
  book_id: 'book-1',
  book_title: 'Alpha',
  status: 'completed',
  created_at: '2026-10-21T12:00:00Z',
  estimated_cost_usd: 1.5,
  calls: {
    firecrawl: 4,
    firecrawl_failed: 1,
    firecrawl_mode: { markdown: 3, markdown_summary: 1, json: 0 },
    perplexity: 6,
    perplexity_failed: 0,
    tavily: 0,
    tavily_failed: 0,
    content_safety: 12,
    content_safety_failed: 2,
  },
  tokens_by_bucket: {
    planning: { input_tokens: 1000, output_tokens: 100, cached_input_tokens: null },
    generation: { input_tokens: 5000, output_tokens: 400 },
    research: {
      input_tokens: 900, output_tokens: 90, perplexity_input_tokens: 400, perplexity_output_tokens: 60,
    },
    guard: { input_tokens: 300, output_tokens: 20, cached_input_tokens: 100 },
  },
}

function mockScreen(books) {
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
  api.analyticsBooksList.mockResolvedValue({ total: books.length, books })
  api.spendByBooks.mockResolvedValue({ degraded: false, charges: [] })
}

async function expandBook(title) {
  const user = userEvent.setup()
  await screen.findByText(title)
  const toggle = screen.getByRole('button', { name: `Show calls and tokens for ${title}` })
  await user.click(toggle)
  return { user, toggle }
}

const rowOf = (table, label) => within(table).getByText(label).closest('tr')
const cellsOf = (tr) => within(tr).getAllByRole('cell').map((td) => td.textContent)

describe('Analytics per-book calls and tokens', () => {
  beforeEach(() => vi.clearAllMocks())

  it('is collapsed by default and expands to both tables', async () => {
    mockScreen([BOOK_WITH_USAGE])
    render(<Analytics />)
    await screen.findByText('Alpha')
    expect(screen.queryByText('Calls by system')).not.toBeInTheDocument()

    const { toggle } = await expandBook('Alpha')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Calls by system')).toBeInTheDocument()
    expect(screen.getByText('Tokens by category')).toBeInTheDocument()

    const [callsTable, tokensTable] = screen.getAllByRole('table').slice(-2)
    expect(cellsOf(rowOf(callsTable, 'Firecrawl'))).toEqual(['Firecrawl', '4', '1'])
    expect(cellsOf(rowOf(callsTable, 'Perplexity'))).toEqual(['Perplexity', '6', '0'])
    expect(cellsOf(rowOf(callsTable, 'markdown'))).toEqual(['mode: markdown', '3', '—'])
    expect(cellsOf(rowOf(callsTable, 'markdown_summary'))).toEqual(['mode: markdown_summary', '1', '—'])

    expect(cellsOf(rowOf(tokensTable, 'Writing'))).toEqual(['Writing', '5,000', '—', '400'])
    expect(cellsOf(rowOf(tokensTable, 'Planning'))).toEqual(['Planning', '1,000', '—', '100'])
    expect(cellsOf(rowOf(tokensTable, 'Safety checks'))).toEqual(['Safety checks', '300', '100', '20'])
    expect(cellsOf(rowOf(tokensTable, 'Research'))).toEqual(['Research', '900', '—', '90'])
    expect(within(tokensTable).getAllByRole('row').map((tr) => tr.firstChild.textContent)).toEqual(
      ['Category', 'Writing', 'Planning', 'Research', 'Perplexity', 'Safety checks'],
    )
  })

  it('hides systems with no requests and modes with a zero count', async () => {
    mockScreen([BOOK_WITH_USAGE])
    render(<Analytics />)
    await expandBook('Alpha')
    const [callsTable] = screen.getAllByRole('table').slice(-2)
    expect(within(callsTable).queryByText('Tavily')).not.toBeInTheDocument()
    expect(within(callsTable).queryByText('Serper')).not.toBeInTheDocument()
    expect(within(callsTable).queryByText('Prompt Shields')).not.toBeInTheDocument()
    expect(within(callsTable).queryByText('json')).not.toBeInTheDocument()
  })

  it('shows failed counts per system', async () => {
    mockScreen([BOOK_WITH_USAGE])
    render(<Analytics />)
    await expandBook('Alpha')
    const [callsTable] = screen.getAllByRole('table').slice(-2)
    expect(cellsOf(rowOf(callsTable, 'Azure Content Safety'))).toEqual(['Azure Content Safety', '12', '2'])
  })

  it('shows the fallback text when a row has no calls or tokens', async () => {
    const { calls: _calls, tokens_by_bucket: _tokens, ...olderRow } = BOOK_WITH_USAGE
    mockScreen([
      { ...olderRow, book_id: 'book-old', book_title: 'Older' },
      { ...olderRow, book_id: 'book-empty', book_title: 'Empty', calls: {}, tokens_by_bucket: {} },
    ])
    render(<Analytics />)
    await expandBook('Older')
    expect(screen.getByText('No call data recorded')).toBeInTheDocument()
    await expandBook('Empty')
    expect(screen.getAllByText('No call data recorded')).toHaveLength(2)
    expect(screen.queryByText('Calls by system')).not.toBeInTheDocument()
  })

  it('collapses again', async () => {
    mockScreen([BOOK_WITH_USAGE])
    render(<Analytics />)
    const { user } = await expandBook('Alpha')
    const hide = screen.getByRole('button', { name: 'Hide calls and tokens for Alpha' })
    await user.click(hide)
    expect(screen.queryByText('Calls by system')).not.toBeInTheDocument()
    expect(screen.queryByText('Tokens by category')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show calls and tokens for Alpha' }))
      .toHaveAttribute('aria-expanded', 'false')
  })
})
