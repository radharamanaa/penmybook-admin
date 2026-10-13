import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

const ACTION_LABEL = { trust: 'Trusted (added)', suspend: 'Suspended' }

/**
 * Runtime changes to the trusted-site list. A page from a trusted site skips the paid safety
 * check; the list itself ships with the worker. Here an operator adds a site to it or suspends
 * one, without a deploy. The worker picks a change up within a minute.
 */
export default function TrustedSites() {
  const [overrides, setOverrides] = useState([])
  const [site, setSite] = useState('')
  const [action, setAction] = useState('suspend')
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)

  const load = useCallback(async () => {
    try {
      const answer = await api.listTrustedSites()
      setOverrides(answer?.overrides ?? [])
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function save(e) {
    e.preventDefault()
    setError(null)
    setOk(null)
    setBusy(true)
    try {
      const answer = await api.putTrustedSite(site.trim(), action, reason.trim())
      const saved = answer?.applied?.site ?? site.trim()
      setOk(action === 'suspend'
        ? `${saved} is suspended. Its pages are checked from now on, and its unchecked pages are withdrawn within a minute.`
        : `${saved} is trusted from now on.`)
      setSite('')
      setReason('')
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function remove(target) {
    setError(null)
    setOk(null)
    try {
      await api.deleteTrustedSite(target)
      setOk(`The change for ${target} is removed. The site is back to what the shipped list says.`)
      setConfirming(null)
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <>
      <h1>Trusted sites</h1>
      <p className="sub">
        Pages from trusted sites skip the paid safety check. The list ships with the worker; add a
        site to it or suspend one here. A change takes effect within a minute, with no deploy.
        Suspending a site also withdraws its unchecked pages from books that hold them and flags
        those chapters for review.
      </p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}

      <div className="panel">
        <h2>Add or suspend a site</h2>
        <form onSubmit={save}>
          <label htmlFor="trusted-site">Site</label>
          <input id="trusted-site" value={site} placeholder="docs.example.org" required
                 onChange={(e) => setSite(e.target.value)} />
          <label htmlFor="trusted-action">Change</label>
          <select id="trusted-action" value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="suspend">Suspend: always check this site</option>
            <option value="trust">Trust: skip the check for this site</option>
          </select>
          <label htmlFor="trusted-reason">Reason</label>
          <input id="trusted-reason" value={reason} minLength={3} maxLength={500} required
                 placeholder="Why this change is being made" onChange={(e) => setReason(e.target.value)} />
          <button disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
        </form>
      </div>

      <div className="panel">
        <h2>Changes in force</h2>
        {overrides.length === 0 ? (
          <p className="sub">No changes. Every site is as the shipped list says.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Site</th><th>Change</th><th>Reason</th><th>By</th><th>When</th><th /></tr></thead>
              <tbody>
                {overrides.map((row) => (
                  <tr key={row.site}>
                    <td className="mono">{row.site}</td>
                    <td>{ACTION_LABEL[row.action] ?? row.action}</td>
                    <td>{row.reason}</td>
                    <td className="mono">{row.who}</td>
                    <td className="mono">{row.when}</td>
                    <td>
                      {confirming === row.site ? (
                        <>
                          <button className="small danger" onClick={() => remove(row.site)}>Confirm removal</button>{' '}
                          <button className="small ghost" onClick={() => setConfirming(null)}>Keep</button>
                        </>
                      ) : (
                        <button className="small ghost" onClick={() => setConfirming(row.site)}>Remove</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
