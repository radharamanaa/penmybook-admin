import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

const GENRE_LABELS = {
  technical: 'Technical',
  science_fiction: 'Science fiction',
  literary_fiction: 'Literary fiction',
  screenplay: 'Screenplay',
  childrens: "Children's",
  unknown: 'Unknown',
}
const GENRE_ORDER = Object.keys(GENRE_LABELS)
const GENRE_N_MAX = 12

const PERIODS = ['last_week', 'last_month', 'last_3_months', 'last_year']

/**
 * Cutover for per-book COGS vs charges: older books lack editor token COGS and
 * may have truncated charge history — variance would mislead, so the list window
 * never starts before this date. Env-driven (VITE_CHARGES_CUTOVER) so it's set
 * per-environment: a QA date to hide pre-feature books, EMPTY on a fresh account
 * (e.g. prod) where every book is fully accounted → show all, no cutover.
 */
const CHARGES_COMPARISON_CUTOVER = import.meta.env.VITE_CHARGES_CUTOVER || ''

const PERIOD_DAYS = {
  last_week: 7,
  last_month: 30,
  last_3_months: 90,
  last_year: 365,
}

const fmtUsd = (cents) => {
  const c = cents ?? 0
  return `${c < 0 ? '-' : ''}$${(Math.abs(c) / 100).toFixed(2)}`
}
const usdToCents = (usd) => Math.round((usd ?? 0) * 100)

function periodWindow(period) {
  const to = new Date()
  const from = new Date(to.getTime() - (PERIOD_DAYS[period] ?? 7) * 86400000)
  // No cutover configured (fresh account) → clamp only to the period; show all books.
  if (!CHARGES_COMPARISON_CUTOVER) {
    return { fromDate: from.toISOString(), toDate: to.toISOString() }
  }
  const cutover = new Date(CHARGES_COMPARISON_CUTOVER)
  return {
    fromDate: (from > cutover ? from : cutover).toISOString(),
    toDate: to.toISOString(),
  }
}

/** Conversations/books/cost analytics + per-book COGS vs charges (post-cutover). */
export default function Analytics() {
  const [period, setPeriod] = useState('last_week')
  const [conv, setConv] = useState(null)
  const [books, setBooks] = useState(null)
  const [live, setLive] = useState(null)
  const [bookRows, setBookRows] = useState([])
  const [bookTotal, setBookTotal] = useState(0)
  const [bookPage, setBookPage] = useState(1)
  const [chargesDegraded, setChargesDegraded] = useState(false)
  const [error, setError] = useState(null)
  const [genreN, setGenreN] = useState(8)
  const [genreCounts, setGenreCounts] = useState(null)
  const [genreError, setGenreError] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    setChargesDegraded(false)
    try {
      const { fromDate, toDate } = periodWindow(period)
      const [c, b, l, list] = await Promise.all([
        api.analyticsConversations(period),
        api.analyticsBooks(period),
        api.analyticsLive(),
        api.analyticsBooksList({ period, fromDate, toDate, page: bookPage, limit: 20 }),
      ])
      setConv(c)
      setBooks(b)
      setLive(l)
      setBookTotal(list.total ?? 0)

      const booksList = list.books ?? []
      const ids = booksList.map((row) => row.book_id).filter(Boolean)
      let chargeMap = {}
      if (ids.length > 0) {
        try {
          // admin-api returns { degraded, charges }. `degraded` is set when the
          // entitlements lookup fail-softed, so an all-zero table is distinguishable
          // from a genuine "no charges" result. A throw here = admin-api unreachable.
          const resp = await api.spendByBooks(ids)
          if (resp?.degraded) setChargesDegraded(true)
          for (const row of resp?.charges ?? []) {
            chargeMap[row.bookId] = row
          }
        } catch {
          setChargesDegraded(true)
          chargeMap = {}
        }
      }

      setBookRows(booksList.map((row) => {
        const costCents = usdToCents(row.estimated_cost_usd)
        const chargedCents = chargeMap[row.book_id]?.chargedCents ?? 0
        return {
          ...row,
          costCents,
          chargedCents,
          deltaCents: chargedCents - costCents,
        }
      }))
    } catch (err) {
      setError(err.message)
    }
  }, [period, bookPage])

  useEffect(() => { load() }, [load])

  const loadGenre = useCallback(async () => {
    setGenreError(null)
    try {
      setGenreCounts(await api.analyticsGenreCounts(genreN))
    } catch (err) {
      setGenreCounts(null)
      setGenreError(err.status === 504
        ? 'Genre counts timed out (UTC windows). Retry, or lower n.'
        : (err.message || 'Genre counts failed'))
    }
  }, [genreN])

  useEffect(() => { loadGenre() }, [loadGenre])

  const pageCount = Math.max(1, Math.ceil(bookTotal / 20))

  return (
    <>
      <h1>Analytics</h1>
      <p className="sub">Token usage + cost-accounting (rates set under Cost-accounting rates).</p>
      {error && <div className="msg error">{error}</div>}
      <div className="row" style={{ marginBottom: 14 }}>
        <div>
          <label htmlFor="period">Period</label>
          <select id="period" value={period} onChange={(e) => { setBookPage(1); setPeriod(e.target.value) }}>
            {PERIODS.map((p) => <option key={p} value={p}>{p.replaceAll('_', ' ')}</option>)}
          </select>
        </div>
      </div>

      {live && (
        <div className="panel stats">
          <div><div className="stat">{live.books_planning}</div><div className="stat-label">planning</div></div>
          <div><div className="stat">{live.books_generating}</div><div className="stat-label">generating</div></div>
          <div><div className="stat">{live.books_chapters_complete}</div><div className="stat-label">complete</div></div>
          <div><div className="stat">{live.books_publishing}</div><div className="stat-label">publishing</div></div>
          <div><div className="stat">{live.total_in_flight}</div><div className="stat-label">in flight</div></div>
        </div>
      )}

      {conv && (
        <div className="panel">
          <h2 style={{ marginTop: 0 }}>Planning conversations</h2>
          <div className="stats">
            <div><div className="stat">{conv.conversation_count}</div><div className="stat-label">conversations</div></div>
            <div><div className="stat">{conv.converted_to_book}</div><div className="stat-label">became books</div></div>
            <div><div className="stat">${conv.avg_cost_usd}</div><div className="stat-label">avg cost</div></div>
            <div><div className="stat">${conv.total_cost_usd}</div><div className="stat-label">total cost</div></div>
          </div>
        </div>
      )}

      {books && (
        <div className="panel">
          <h2 style={{ marginTop: 0 }}>Book generation</h2>
          <div className="stats">
            <div><div className="stat">{books.books_generated}</div><div className="stat-label">books generated</div></div>
            <div><div className="stat">{Math.round(books.avg_total_tokens).toLocaleString()}</div>
              <div className="stat-label">avg tokens/book</div></div>
            <div><div className="stat">{books.avg_images_per_book}</div><div className="stat-label">avg images</div></div>
            <div><div className="stat">${books.avg_cost_usd}</div><div className="stat-label">avg cost</div></div>
            <div><div className="stat">${books.total_cost_usd}</div><div className="stat-label">total cost</div></div>
          </div>
        </div>
      )}

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Books by genre</h2>
        <p className="sub">
          UTC windows — today, this ISO week (Monday 00:00), last {genreN} weeks.
          Started = created; finished = generation_finished_at only (no created_at fallback).
          Independent of the COGS period picker.
        </p>
        <div className="row" style={{ marginBottom: 12 }}>
          <div>
            <label htmlFor="genre-n">Weeks (n)</label>
            <select
              id="genre-n"
              value={genreN}
              onChange={(e) => setGenreN(Number(e.target.value))}
            >
              {Array.from({ length: GENRE_N_MAX }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </div>
        </div>
        {genreError && <div className="msg error">{genreError}</div>}
        {genreCounts && (
          <>
            <p className="sub">
              Legacy finished without a stamp: {genreCounts.legacy_finished_unreliable} (unreliable — not in the finished series).
            </p>
            <table>
              <thead>
                <tr>
                  <th>Genre</th>
                  <th>Started today (UTC)</th>
                  <th>Finished today (UTC)</th>
                  <th>Started this week (UTC)</th>
                  <th>Finished this week (UTC)</th>
                </tr>
              </thead>
              <tbody>
                {GENRE_ORDER.map((g) => (
                  <tr key={g}>
                    <td>{GENRE_LABELS[g]}</td>
                    <td>{genreCounts.today?.started?.[g] ?? 0}</td>
                    <td>{genreCounts.today?.finished?.[g] ?? 0}</td>
                    <td>{genreCounts.this_week?.started?.[g] ?? 0}</td>
                    <td>{genreCounts.this_week?.finished?.[g] ?? 0}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <h3>Last {genreCounts.n} ISO weeks (UTC Monday–Sunday)</h3>
            <table>
              <thead>
                <tr>
                  <th>Week start (UTC)</th>
                  {GENRE_ORDER.map((g) => (
                    <th key={`s-${g}`}>{GENRE_LABELS[g]} started</th>
                  ))}
                  {GENRE_ORDER.map((g) => (
                    <th key={`f-${g}`}>{GENRE_LABELS[g]} finished</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(genreCounts.weeks ?? []).map((w) => (
                  <tr key={w.week_start}>
                    <td className="mono">{w.week_start}</td>
                    {GENRE_ORDER.map((g) => (
                      <td key={`s-${w.week_start}-${g}`}>{w.started?.[g] ?? 0}</td>
                    ))}
                    {GENRE_ORDER.map((g) => (
                      <td key={`f-${w.week_start}-${g}`}>{w.finished?.[g] ?? 0}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Per-book COGS vs charges</h2>
        <p className="sub">
          {CHARGES_COMPARISON_CUTOVER
            ? `Books created on/after ${CHARGES_COMPARISON_CUTOVER.slice(0, 10)}. `
            : 'All books. '}
          COGS from token usage; charges billed are gross (pre-refund). Δ = charges − cost (cents).
        </p>
        {chargesDegraded && (
          <div className="msg error">
            Charges lookup failed — showing $0.00 billed (entitlements may be down).
          </div>
        )}
        <table>
          <thead>
            <tr>
              <th>Book</th>
              <th>Status</th>
              <th>Created</th>
              <th>COGS (tokens)</th>
              <th>Charges billed (gross, pre-refund)</th>
              <th>Δ (charges − cost)</th>
            </tr>
          </thead>
          <tbody>
            {bookRows.length === 0 ? (
              <tr><td colSpan={6} className="sub">No books in this period.</td></tr>
            ) : bookRows.map((row) => (
              <tr key={row.book_id}>
                <td>
                  <div>{row.book_title || '(untitled)'}</div>
                  <div className="mono sub">{row.book_id}</div>
                </td>
                <td className="mono">{row.status}</td>
                <td>{row.created_at ? row.created_at.slice(0, 10) : '—'}</td>
                <td>{fmtUsd(row.costCents)}</td>
                <td>{fmtUsd(row.chargedCents)}</td>
                <td>{fmtUsd(row.deltaCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {bookTotal > 20 && (
          <div className="row" style={{ marginTop: 12, gap: 8, alignItems: 'center' }}>
            <button type="button" disabled={bookPage <= 1}
                    onClick={() => setBookPage((p) => Math.max(1, p - 1))}>Prev</button>
            <span className="sub">Page {bookPage} / {pageCount} ({bookTotal} books)</span>
            <button type="button" disabled={bookPage >= pageCount}
                    onClick={() => setBookPage((p) => p + 1)}>Next</button>
          </div>
        )}
      </div>
    </>
  )
}
