import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

function fmtDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}

/**
 * Feedback triage — newest-first table + screenshot thumbnails + resolve-with-note.
 * All text via React escaping (no dangerouslySetInnerHTML).
 */
export default function Feedback() {
  const [items, setItems] = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [thumbUrls, setThumbUrls] = useState({}) // screenshot_id -> object URL
  const [resolveId, setResolveId] = useState(null)
  const [resolveNote, setResolveNote] = useState('')
  const [resolveBusy, setResolveBusy] = useState(false)
  const thumbUrlsRef = useRef({})

  const revokeAllThumbs = useCallback(() => {
    for (const url of Object.values(thumbUrlsRef.current)) {
      URL.revokeObjectURL(url)
    }
    thumbUrlsRef.current = {}
    setThumbUrls({})
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    revokeAllThumbs()
    try {
      const resp = await api.feedbackList({ page, limit: 20 })
      const rows = resp.items ?? []
      setItems(rows)
      setTotal(resp.total ?? 0)

      // Load thumbnails for visible screenshot_ids (object URLs revoked on page change).
      const next = {}
      const ids = rows.flatMap((r) => r.screenshot_ids || []).slice(0, 40)
      await Promise.all(
        ids.map(async (id) => {
          try {
            const blob = await api.fetchFeedbackScreenshotBlob(id)
            next[id] = URL.createObjectURL(blob)
          } catch {
            /* missing/unauthorized — skip thumb */
          }
        }),
      )
      thumbUrlsRef.current = next
      setThumbUrls(next)
    } catch (err) {
      setError(err.message)
      setItems([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page, revokeAllThumbs])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    return () => {
      for (const url of Object.values(thumbUrlsRef.current)) {
        URL.revokeObjectURL(url)
      }
      thumbUrlsRef.current = {}
    }
  }, [])

  const pageCount = Math.max(1, Math.ceil(total / 20))

  async function confirmResolve() {
    if (!resolveId || !resolveNote.trim() || resolveBusy) return
    setResolveBusy(true)
    setError(null)
    try {
      await api.resolveFeedback(resolveId, resolveNote.trim())
      setResolveId(null)
      setResolveNote('')
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setResolveBusy(false)
    }
  }

  return (
    <div>
      <h1>Feedback</h1>
      <p className="sub">Beta submissions — resolve writes an audit row then hard-deletes.</p>
      {error && <div className="msg error">{error}</div>}
      {loading && <p className="sub">Loading…</p>}

      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Context</th>
            <th>Rating</th>
            <th>Category</th>
            <th>Message</th>
            <th>Shots</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <tr key={row.feedback_id}>
              <td className="mono">{fmtDate(row.created_at)}</td>
              <td>{row.context || '—'}</td>
              <td>{row.rating != null ? row.rating : '—'}</td>
              <td>{row.category || '—'}</td>
              <td style={{ maxWidth: 320, whiteSpace: 'pre-wrap' }}>
                {row.message || '—'}
              </td>
              <td>
                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                  {(row.screenshot_ids || []).map((id) =>
                    thumbUrls[id] ? (
                      <a key={id} href={thumbUrls[id]} target="_blank" rel="noreferrer">
                        <img
                          src={thumbUrls[id]}
                          alt="screenshot"
                          style={{
                            width: 48,
                            height: 48,
                            objectFit: 'cover',
                            borderRadius: 4,
                          }}
                        />
                      </a>
                    ) : (
                      <span key={id} className="sub" style={{ fontSize: 11 }}>…</span>
                    ),
                  )}
                </div>
              </td>
              <td>
                <button
                  type="button"
                  className="small"
                  onClick={() => {
                    setResolveId(row.feedback_id)
                    setResolveNote('')
                  }}
                >
                  Resolve
                </button>
              </td>
            </tr>
          ))}
          {!loading && items.length === 0 && (
            <tr>
              <td colSpan={7} className="sub">No feedback yet.</td>
            </tr>
          )}
        </tbody>
      </table>

      {total > 20 && (
        <div className="row" style={{ marginTop: 12, gap: 8, alignItems: 'center' }}>
          <button
            type="button"
            className="ghost small"
            disabled={page <= 1}
            onClick={() => setPage((p) => Math.max(1, p - 1))}
          >
            Prev
          </button>
          <span className="sub">
            Page {page} / {pageCount} ({total} items)
          </span>
          <button
            type="button"
            className="ghost small"
            disabled={page >= pageCount}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}

      {resolveId && (
        <div className="panel" style={{ marginTop: 16, maxWidth: 480 }}>
          <h2 style={{ fontSize: 16, marginTop: 0 }}>Resolve feedback</h2>
          <p className="sub mono">{resolveId}</p>
          <label htmlFor="resolve-note">Resolution note</label>
          <textarea
            id="resolve-note"
            rows={4}
            value={resolveNote}
            onChange={(e) => setResolveNote(e.target.value.slice(0, 2000))}
            required
            style={{ width: '100%', marginBottom: 8 }}
          />
          <div className="row" style={{ gap: 8 }}>
            <button
              type="button"
              disabled={resolveBusy || !resolveNote.trim()}
              onClick={confirmResolve}
            >
              {resolveBusy ? 'Resolving…' : 'Confirm resolve'}
            </button>
            <button
              type="button"
              className="ghost"
              disabled={resolveBusy}
              onClick={() => {
                setResolveId(null)
                setResolveNote('')
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
