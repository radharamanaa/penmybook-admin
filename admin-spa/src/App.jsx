import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { api, hasToken, onAuthExpired, setToken } from './api.js'

// Portal sections, mirroring docs/admin-control-plane/PARAMETERS.md.
const CONFIG_SECTIONS = [
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

  return (
    <div className="layout">
      <nav className="sidebar">
        <div className="brand">PenMyBook<small>admin control plane</small></div>
        <div className="group">Operate</div>
        <NavLink to="/" end>Operations</NavLink>
        <NavLink to="/analytics">Analytics</NavLink>
        <div className="group">Configure</div>
        {CONFIG_SECTIONS.map((s) => (
          <NavLink key={s.key} to={`/config/${s.key}`}>{s.label}</NavLink>
        ))}
        <NavLink to="/pricing">Pricing & billing</NavLink>
        <NavLink to="/system">System (read-only)</NavLink>
        <div className="group">People</div>
        <NavLink to="/users">Users & money</NavLink>
        <NavLink to="/waitlist">Waitlist</NavLink>
        <div className="group">Govern</div>
        <NavLink to="/accounts">Admin accounts</NavLink>
        <NavLink to="/audit">Audit log</NavLink>
        <div className="foot">
          <div className="mono" style={{ marginBottom: 8 }}>{adminEmail}</div>
          <button className="ghost small" onClick={logout}>Sign out</button>
        </div>
      </nav>
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
