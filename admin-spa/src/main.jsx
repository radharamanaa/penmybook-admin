import React from 'react'
import ReactDOM from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import { PaletteProvider } from './PaletteProvider.jsx'
import { PaletteSwitcher } from './components/PaletteSwitcher.jsx'
import App from './App.jsx'
import Operations from './screens/Operations.jsx'
import ConfigSection from './screens/ConfigSection.jsx'
import Pricing from './screens/Pricing.jsx'
import Users from './screens/Users.jsx'
import Signups from './screens/Signups.jsx'
import Waitlist from './screens/Waitlist.jsx'
import Analytics from './screens/Analytics.jsx'
import Feedback from './screens/Feedback.jsx'
import Spend from './screens/Spend.jsx'
import AdminAccounts from './screens/AdminAccounts.jsx'
import Audit from './screens/Audit.jsx'
import SystemView from './screens/SystemView.jsx'
import TrustedSites from './screens/TrustedSites.jsx'
import RefundRequests from './screens/RefundRequests.jsx'
import MoneyAlerts from './screens/MoneyAlerts.jsx'
import './styles.css'

const REFUND_QUEUE_ENABLED = import.meta.env.VITE_ENABLE_REFUND_QUEUE === 'true'

const router = createBrowserRouter([
  {
    element: <App />,
    children: [
      { path: '/', element: <Operations /> },
      { path: '/config/:section', element: <ConfigSection /> },
      { path: '/pricing', element: <Pricing /> },
      { path: '/trusted-sites', element: <TrustedSites /> },
      { path: '/signups', element: <Signups /> },
      { path: '/users', element: <Users /> },
      { path: '/waitlist', element: <Waitlist /> },
      { path: '/analytics', element: <Analytics /> },
      { path: '/feedback', element: <Feedback /> },
      { path: '/spend', element: <Spend /> },
      ...(REFUND_QUEUE_ENABLED
        ? [{ path: '/refund-requests', element: <RefundRequests /> }]
        : []),
      { path: '/money-alerts', element: <MoneyAlerts /> },
      { path: '/accounts', element: <AdminAccounts /> },
      { path: '/audit', element: <Audit /> },
      { path: '/system', element: <SystemView /> },
    ],
  },
])

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <PaletteProvider>
      <PaletteSwitcher />
      <RouterProvider router={router} />
    </PaletteProvider>
  </React.StrictMode>,
)
