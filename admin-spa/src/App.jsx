import { useEffect, useState } from 'react'
import { api, hasToken, onAuthExpired, setToken } from './api.js'
import { AdminShell } from './AdminShell.jsx'

// Portal sections, mirroring docs/admin-control-plane/PARAMETERS.md.
export const CONFIG_SECTIONS = [
  { key: 'tiers', label: 'Tiers & caps' },
  { key: 'safety', label: 'Content safety' },
  { key: 'moderation', label: 'Moderation & access' },
  { key: 'budgets', label: 'Gen & research budgets' },
  { key: 'cost_rates', label: 'Cost-accounting rates' },
]

function Login({ onLoggedIn }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const resp = await api.login(email, password)
      setToken(resp.access_token)
      onLoggedIn(email)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="panel login-card" onSubmit={submit}>
        <h1>PenMyBook Admin</h1>
        <p className="sub">Operator sign-in</p>
        {error && <div className="msg error">{error}</div>}
        <label htmlFor="email">Email</label>
        <input id="email" type="email" value={email} autoComplete="username"
               onChange={(e) => setEmail(e.target.value)} required />
        <label htmlFor="password">Password</label>
        <input id="password" type="password" value={password} autoComplete="current-password"
               onChange={(e) => setPassword(e.target.value)} required />
        <button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  )
}

export default function App() {
  const [adminEmail, setAdminEmail] = useState(null)
  // In-memory token (D7): a refresh re-prompts; 401 anywhere returns to login.
  const [, forceRender] = useState(0)
  useEffect(() => {
    onAuthExpired(() => {
      setAdminEmail(null)
      forceRender((n) => n + 1)
    })
  }, [])

  if (!hasToken() || !adminEmail) return <Login onLoggedIn={setAdminEmail} />

  async function logout() {
    try { await api.logout() } catch { /* token may already be dead */ }
    setToken(null)
    setAdminEmail(null)
  }

  return <AdminShell adminEmail={adminEmail} onLogout={logout} sections={CONFIG_SECTIONS} />
}
