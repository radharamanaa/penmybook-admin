import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const moneyAlerts = vi.hoisted(() => vi.fn())
const acknowledgeMoneyAlert = vi.hoisted(() => vi.fn())
vi.mock('../api.js', () => ({
  api: {
    moneyAlerts: (...a) => moneyAlerts(...a),
    acknowledgeMoneyAlert: (...a) => acknowledgeMoneyAlert(...a),
  },
}))

import MoneyAlerts from './MoneyAlerts.jsx'

const OPEN_ALERT = {
  id: 'alert-open',
  alertType: 'REFUND_HEAL_SHORTFALL',
  severity: 'CRITICAL',
  subject: 'Refund shortfall',
  body: 'The wallet could not cover the refund.',
  ownerType: 'USER',
  ownerId: 'owner-1',
  paymentId: 'payment-1',
  createdAt: '2026-10-30T11:00:00Z',
  emailError: 'not sent — alert email is disabled',
  acknowledgedAt: null,
  acknowledgedBy: null,
  resolutionNote: null,
}

const CLOSED_ALERT = {
  ...OPEN_ALERT,
  id: 'alert-closed',
  alertType: 'DISPUTE_CURRENCY',
  severity: 'WARNING',
  subject: 'Dispute in another currency',
  emailError: null,
  acknowledgedAt: '2026-10-30T12:00:00Z',
  acknowledgedBy: 'staff-9',
  resolutionNote: 'Checked in Stripe',
}

describe('MoneyAlerts', () => {
  beforeEach(() => {
    moneyAlerts.mockReset()
    acknowledgeMoneyAlert.mockReset()
    moneyAlerts.mockResolvedValue({ degraded: false, alerts: [OPEN_ALERT] })
  })

  it('lists open alerts with severity, code, title, detail and a never-emailed marker', async () => {
    render(<MoneyAlerts />)
    expect(await screen.findByText('Refund shortfall')).toBeInTheDocument()
    expect(moneyAlerts).toHaveBeenCalledWith('OPEN', null, 100)
    const row = screen.getByText('Refund shortfall').closest('tr')
    expect(within(row).getByText('CRITICAL')).toHaveClass('badge', 'off')
    expect(screen.getByText('REFUND_HEAL_SHORTFALL')).toBeInTheDocument()
    expect(screen.getByText('The wallet could not cover the refund.')).toBeInTheDocument()
    expect(screen.getByText(/email not sent/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Acknowledge / })).toBeInTheDocument()
  })

  it('shows who closed an alert and why under All, with no Acknowledge button', async () => {
    moneyAlerts.mockImplementation(async (status) => ({
      degraded: false,
      alerts: status === 'ALL' ? [CLOSED_ALERT, OPEN_ALERT] : [OPEN_ALERT],
    }))
    render(<MoneyAlerts />)
    await screen.findByText('Refund shortfall')
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(moneyAlerts).toHaveBeenLastCalledWith('ALL', null, 100)
    const closedRow = (await screen.findByText('Dispute in another currency')).closest('tr')
    expect(within(closedRow).getByText('staff-9')).toBeInTheDocument()
    expect(within(closedRow).getByText('Checked in Stripe')).toBeInTheDocument()
    expect(within(closedRow).queryByRole('button', { name: /^Acknowledge / })).toBeNull()
  })

  it('filters by severity', async () => {
    render(<MoneyAlerts />)
    await screen.findByText('Refund shortfall')
    await userEvent.click(screen.getByRole('button', { name: 'WARNING' }))
    expect(moneyAlerts).toHaveBeenLastCalledWith('OPEN', 'WARNING', 100)
  })

  it('a slow answer for an earlier filter never replaces the newest filter\'s rows', async () => {
    render(<MoneyAlerts />)
    await screen.findByText('Refund shortfall')
    let answerAll
    moneyAlerts.mockImplementationOnce(() => new Promise((resolve) => { answerAll = resolve }))
    moneyAlerts.mockResolvedValueOnce({ degraded: false, alerts: [] })
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    await userEvent.click(screen.getByRole('button', { name: 'WARNING' }))
    expect(await screen.findByText(/No alerts/i)).toBeInTheDocument()
    answerAll({ degraded: false, alerts: [OPEN_ALERT] })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByText('Refund shortfall')).toBeNull()
  })

  it('acknowledges with a required note after confirming, then reloads', async () => {
    acknowledgeMoneyAlert.mockResolvedValue({ ...OPEN_ALERT, acknowledgedAt: '2026-10-30T13:00:00Z' })
    render(<MoneyAlerts />)
    await userEvent.click(await screen.findByRole('button', { name: /^Acknowledge / }))
    const confirm = screen.getByRole('button', { name: 'Confirm acknowledge' })
    expect(confirm).toBeDisabled()
    await userEvent.type(screen.getByLabelText('What did you do about it?'), 'Refunded by hand')
    moneyAlerts.mockResolvedValue({ degraded: false, alerts: [] })
    await userEvent.click(confirm)
    expect(acknowledgeMoneyAlert).toHaveBeenCalledWith('alert-open', 'Refunded by hand')
    expect(await screen.findByText(/No open alerts/i)).toBeInTheDocument()
    expect(moneyAlerts).toHaveBeenCalledTimes(2)
  })

  it('cancel closes the confirm panel without calling the API', async () => {
    render(<MoneyAlerts />)
    await userEvent.click(await screen.findByRole('button', { name: /^Acknowledge / }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Confirm acknowledge' })).toBeNull()
    expect(acknowledgeMoneyAlert).not.toHaveBeenCalled()
  })

  it('never shows a billing outage as an empty list', async () => {
    moneyAlerts.mockResolvedValue({ degraded: true, alerts: [] })
    render(<MoneyAlerts />)
    expect(await screen.findByText(/Billing did not answer/i)).toBeInTheDocument()
    expect(screen.queryByText(/No open alerts/i)).toBeNull()
  })
})
