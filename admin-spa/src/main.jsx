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
import Waitlist from './screens/Waitlist.jsx'
import Analytics from './screens/Analytics.jsx'
import AdminAccounts from './screens/AdminAccounts.jsx'
import Audit from './screens/Audit.jsx'
import SystemView from './screens/SystemView.jsx'
import './styles.css'

const router = createBrowserRouter([
  {
    element: <App />,
    children: [
      { path: '/', element: <Operations /> },
      { path: '/config/:section', element: <ConfigSection /> },
      { path: '/pricing', element: <Pricing /> },
      { path: '/users', element: <Users /> },
      { path: '/waitlist', element: <Waitlist /> },
      { path: '/analytics', element: <Analytics /> },
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
