import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

const PERIODS = ['last_week', 'last_month', 'last_3_months', 'last_year']

/** Conversations/books/cost analytics + live counters (service-auth path, D15). */
export default function Analytics() {
  const [period, setPeriod] = useState('last_week')
  const [conv, setConv] = useState(null)
  const [books, setBooks] = useState(null)
  const [live, setLive] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [c, b, l] = await Promise.all([
        api.analyticsConversations(period),
        api.analyticsBooks(period),
        api.analyticsLive(),
      ])
      setConv(c)
      setBooks(b)
      setLive(l)
    } catch (err) {
      setError(err.message)
    }
  }, [period])

  useEffect(() => { load() }, [load])

  return (
    <>
      <h1>Analytics</h1>
      <p className="sub">Token usage + cost-accounting (rates set under Cost-accounting rates).</p>
      {error && <div className="msg error">{error}</div>}
      <div className="row" style={{ marginBottom: 14 }}>
        <div>
          <label htmlFor="period">Period</label>
          <select id="period" value={period} onChange={(e) => setPeriod(e.target.value)}>
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
    </>
  )
}
