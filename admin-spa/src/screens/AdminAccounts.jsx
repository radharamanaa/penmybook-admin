import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/** The "two emails today, add more later" surface. Last-admin removal is blocked server-side. */
export default function AdminAccounts() {
  const [admins, setAdmins] = useState([])
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)

  const load = useCallback(async () => {
    try {
      setAdmins(await api.listAdmins())
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function create(e) {
    e.preventDefault()
    setError(null)
    setOk(null)
    try {
      await api.createAdmin(email, password)
      setOk(`Admin ${email} created.`)
      setEmail('')
      setPassword('')
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  async function remove(admin) {
    setError(null)
    setOk(null)
    if (!window.confirm(`Remove admin ${admin.email}? Their sessions die immediately.`)) return
    try {
      await api.deleteAdmin(admin.admin_uuid)
      setOk(`Admin ${admin.email} removed.`)
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  async function resetPassword(admin) {
    const pw = window.prompt(`New password for ${admin.email} (min 12 chars):`)
    if (!pw) return
    setError(null)
    try {
      await api.resetAdminPassword(admin.admin_uuid, pw)
      setOk(`Password reset for ${admin.email}; their old sessions are revoked.`)
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <>
      <h1>Admin accounts</h1>
      <p className="sub">
        Closed set — no self-signup. Removal and password reset revoke live sessions
        (token-version bump); the last admin can never be removed.
      </p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}

      <div className="panel">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Email</th><th>Created</th><th /></tr></thead>
            <tbody>
              {admins.map((a) => (
                <tr key={a.admin_uuid}>
                  <td>{a.email}</td>
                  <td className="mono">{a.created_at}</td>
                  <td>
                    <button className="small ghost" onClick={() => resetPassword(a)}>Reset password</button>{' '}
                    <button className="small danger" onClick={() => remove(a)}
                            disabled={admins.length <= 1}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <form className="panel" onSubmit={create}>
        <h2 style={{ marginTop: 0 }}>Add admin</h2>
        <label htmlFor="new-email">Email</label>
        <input id="new-email" type="email" value={email} required
               onChange={(e) => setEmail(e.target.value)} />
        <label htmlFor="new-pw">Password (min 12 chars)</label>
        <input id="new-pw" type="password" value={password} minLength={12} required
               onChange={(e) => setPassword(e.target.value)} />
        <button>Create admin</button>
      </form>
    </>
  )
}
