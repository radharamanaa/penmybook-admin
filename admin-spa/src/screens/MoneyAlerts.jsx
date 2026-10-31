import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { useScrollIntoViewOnPhone } from '../useScrollIntoViewOnPhone.js'

const FILTERS = [
  { key: 'OPEN', label: 'Open' },
  { key: 'ALL', label: 'All' },
]

const SEVERITIES = [null, 'CRITICAL', 'WARNING']

const SEVERITY_BADGE = {
  CRITICAL: 'off',
  WARNING: 'warn',
}

// Rows asked of admin-api per view.
const ALERT_LIST_LIMIT = 100

function fmtDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}

/**
 * Money alerts billing raised (refund shortfalls, disputes in another currency, double payouts…).
 * Closing one moves no money; the note is the whole record of what staff decided.
 *
 * Two empty states must never look the same:
 *   - billing did not answer (`degraded`) — red banner, the list is not complete
 *   - nothing is open
 */
export default function MoneyAlerts() {
  const [status, setStatus] = useState('OPEN')
  const [severity, setSeverity] = useState(null)
  const [envelope, setEnvelope] = useState(null)
  const [error, setError] = useState(null)
  const [ackAlert, setAckAlert] = useState(null)
  const [ackNote, setAckNote] = useState('')
  const [ackBusy, setAckBusy] = useState(false)
  const ackRef = useRef(null)
  // Only the newest request may fill the table: a quick filter switch must not show one filter's
  // rows under another filter's buttons.
  const loadSeq = useRef(0)
  useScrollIntoViewOnPhone(ackRef, ackAlert)

  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    setError(null)
    try {
      const next = await api.moneyAlerts(status, severity, ALERT_LIST_LIMIT)
      if (seq === loadSeq.current) setEnvelope(next)
    } catch (err) {
      if (seq !== loadSeq.current) return
      setError(err.message)
      setEnvelope(null)
    }
  }, [status, severity])

  useEffect(() => { load() }, [load])

  async function confirmAcknowledge() {
    if (!ackAlert || !ackNote.trim() || ackBusy) return
    setAckBusy(true)
    setError(null)
    try {
      await api.acknowledgeMoneyAlert(ackAlert.id, ackNote.trim())
      setAckAlert(null)
      setAckNote('')
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setAckBusy(false)
    }
  }

  const alerts = envelope?.alerts ?? []
  const degraded = envelope?.degraded === true

  return (
    <>
      <h1>Money alerts</h1>
      <p className="sub">
        Money situations billing raised for a person to decide. Critical ones are also emailed.
        Acknowledging moves no money — your note is the record of what you did.
      </p>
      {error && <div className="msg error" role="alert">{error}</div>}
      {degraded && (
        <div className="msg error">
          Billing did not answer. This is not an empty list — do not treat it as complete.
        </div>
      )}

      <div className="row" style={{ marginTop: 8 }}>
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            className={status === f.key ? 'small' : 'ghost small'}
            onClick={() => setStatus(f.key)}
          >
            {f.label}
          </button>
        ))}
        {SEVERITIES.map((s) => (
          <button
            key={s ?? 'any'}
            type="button"
            className={severity === s ? 'small' : 'ghost small'}
            onClick={() => setSeverity(s)}
          >
            {s ?? 'Any severity'}
          </button>
        ))}
        <button type="button" className="ghost small" onClick={load}>Refresh</button>
      </div>

      {envelope == null && !error ? (
        <p className="sub">Loading…</p>
      ) : envelope == null ? (
        <p className="sub">Could not load the alerts — this is not an empty list.</p>
      ) : !degraded && alerts.length === 0 ? (
        <p className="sub">{status === 'OPEN' ? 'No open alerts.' : 'No alerts.'}</p>
      ) : alerts.length > 0 ? (
        <div className="table-wrap">
          <table className="cards" role="table">
            <thead role="rowgroup">
              <tr role="row">
                <th role="columnheader">Severity</th>
                <th role="columnheader">Code</th>
                <th role="columnheader">Title</th>
                <th role="columnheader">Detail</th>
                <th role="columnheader">Created</th>
                <th role="columnheader">Acknowledged</th>
                <th role="columnheader" aria-label="Action" />
              </tr>
            </thead>
            <tbody role="rowgroup">
              {alerts.map((row) => (
                <tr role="row" key={row.id}>
                  <td role="cell" data-label="Severity">
                    <span className={`badge ${SEVERITY_BADGE[row.severity] ?? 'neutral'}`}>
                      {row.severity}
                    </span>
                    {row.emailError && (
                      <div className="sub" title={row.emailError}>email not sent</div>
                    )}
                  </td>
                  <td role="cell" data-label="Code" className="mono">{row.alertType}</td>
                  <td role="cell" data-label="Title">{row.subject || '—'}</td>
                  <td role="cell" data-label="Detail" style={{ maxWidth: 360, whiteSpace: 'pre-wrap' }}>
                    <div>{row.body || '—'}</div>
                    <div className="mono" style={{ fontSize: 11 }}>
                      {row.ownerType}/{row.ownerId}{row.paymentId ? ` · payment ${row.paymentId}` : ''}
                    </div>
                  </td>
                  <td role="cell" data-label="Created" className="mono">{fmtDate(row.createdAt)}</td>
                  <td role="cell" data-label="Acknowledged">
                    {row.acknowledgedAt ? (
                      <>
                        <div className="mono">{fmtDate(row.acknowledgedAt)}</div>
                        <div className="mono" style={{ fontSize: 11 }}>{row.acknowledgedBy}</div>
                        <div style={{ whiteSpace: 'pre-wrap' }}>{row.resolutionNote}</div>
                      </>
                    ) : '—'}
                  </td>
                  <td role="cell">
                    {!row.acknowledgedAt && (
                      <button
                        type="button"
                        className="small"
                        aria-label={`Acknowledge ${row.subject}`}
                        onClick={() => {
                          setAckAlert(row)
                          setAckNote('')
                        }}
                      >
                        Acknowledge
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {alerts.length >= ALERT_LIST_LIMIT && (
            <p className="sub">Showing the {ALERT_LIST_LIMIT} most recent — there may be more.</p>
          )}
        </div>
      ) : null}

      {ackAlert && (
        <div ref={ackRef} className="panel" style={{ marginTop: 16, maxWidth: 480 }}>
          <h2 style={{ fontSize: 16, marginTop: 0 }}>Acknowledge alert</h2>
          <p className="sub">{ackAlert.subject}</p>
          <p className="sub mono">{ackAlert.id}</p>
          <label htmlFor="ack-note">What did you do about it?</label>
          <textarea
            id="ack-note"
            rows={4}
            value={ackNote}
            onChange={(e) => setAckNote(e.target.value.slice(0, 2000))}
            required
            style={{ width: '100%', marginBottom: 8 }}
          />
          <div className="row" style={{ gap: 8 }}>
            <button
              type="button"
              disabled={ackBusy || !ackNote.trim()}
              onClick={confirmAcknowledge}
            >
              {ackBusy ? 'Acknowledging…' : 'Confirm acknowledge'}
            </button>
            <button
              type="button"
              className="ghost"
              disabled={ackBusy}
              onClick={() => {
                setAckAlert(null)
                setAckNote('')
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </>
  )
}
