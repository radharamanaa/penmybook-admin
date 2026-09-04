import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('../api.js', () => ({
  api: {
    waitlistConfig: vi.fn(),
    waitlistQueue: vi.fn(),
    listPreAdmits: vi.fn(),
    preAdmitEmail: vi.fn(),
    waitlistAdmit: vi.fn(),
    updateWaitlistConfig: vi.fn(),
  },
}))

import Waitlist from './Waitlist.jsx'

const PENDING_ROW = {
  id: 'p1',
  email: 'pending@example.com',
  admittedBy: '019200aa-0000-7000-8000-0000000000aa',
  createdAt: '2026-09-03T10:00:00Z',
  expiresAt: '2026-10-03T10:00:00Z',
}

describe('Waitlist — pre-admit by email', () => {
  let api
  beforeEach(async () => {
    ;({ api } = await import('../api.js'))
    vi.clearAllMocks()
    api.waitlistConfig.mockResolvedValue({ enabled: true, weeklyApprovalLimit: 5, admittedThisWeek: 1 })
    api.waitlistQueue.mockResolvedValue([])
    api.listPreAdmits.mockResolvedValue([PENDING_ROW])
  })

  async function typeAndSubmit(email) {
    await userEvent.type(screen.getByLabelText('Email to pre-admit'), email)
    await userEvent.click(screen.getByRole('button', { name: /^Pre-admit$/ }))
  }

  it('lists pending pre-admits with who added them and when they expire', async () => {
    render(<Waitlist />)
    expect(await screen.findByText('pending@example.com')).toBeInTheDocument()
    expect(screen.getByText(/Pending pre-admits \(1\)/)).toBeInTheDocument()
    expect(screen.getByText(PENDING_ROW.admittedBy)).toBeInTheDocument()
    expect(screen.getByText('2026-10-03T10:00:00Z')).toBeInTheDocument()
  })

  it('hides the pending table when there is nothing pending', async () => {
    api.listPreAdmits.mockResolvedValue([])
    render(<Waitlist />)
    await screen.findByText('Pre-admit by email')
    expect(screen.queryByText(/Pending pre-admits/)).not.toBeInTheDocument()
  })

  it('submits the trimmed email, shows success, clears the input and reloads', async () => {
    api.preAdmitEmail.mockResolvedValue({ admitted: true, reason: null, expiresAt: '2026-10-03T10:00:00Z' })
    render(<Waitlist />)
    await screen.findByText('pending@example.com')

    await typeAndSubmit('  new@example.com  ')

    expect(api.preAdmitEmail).toHaveBeenCalledWith('new@example.com')
    expect(await screen.findByText(/new@example.com pre-admitted/)).toBeInTheDocument()
    expect(screen.getByText(/Expires on 2026-10-03/)).toBeInTheDocument()
    expect(screen.getByLabelText('Email to pre-admit')).toHaveValue('')
    await waitFor(() => expect(api.listPreAdmits).toHaveBeenCalledTimes(2))
  })

  it.each([
    ['duplicate', /already pre-admitted and still pending/],
    ['already_registered', /already has an account/],
    ['weekly_limit_reached', /approval limit is reached/],
    ['invalid_email', /not a valid email address/],
  ])('surfaces the %s reason as an error and keeps the input', async (reason, copy) => {
    api.preAdmitEmail.mockResolvedValue({ admitted: false, reason })
    render(<Waitlist />)
    await screen.findByText('pending@example.com')

    await typeAndSubmit('x@example.com')

    expect(await screen.findByText(copy)).toBeInTheDocument()
    expect(screen.getByLabelText('Email to pre-admit')).toHaveValue('x@example.com')
    // Nothing changed server-side, so no refetch.
    expect(api.listPreAdmits).toHaveBeenCalledTimes(1)
  })

  it('falls back to a generic message for an unknown reason', async () => {
    api.preAdmitEmail.mockResolvedValue({ admitted: false, reason: 'something_new' })
    render(<Waitlist />)
    await screen.findByText('pending@example.com')

    await typeAndSubmit('x@example.com')

    expect(await screen.findByText(/Pre-admit rejected \(something_new\)/)).toBeInTheDocument()
  })

  it('shows the transport error when the request fails', async () => {
    api.preAdmitEmail.mockRejectedValue(new Error('HTTP 503'))
    render(<Waitlist />)
    await screen.findByText('pending@example.com')

    await typeAndSubmit('x@example.com')

    expect(await screen.findByText('HTTP 503')).toBeInTheDocument()
  })

  it('disables the button while the input is empty', async () => {
    render(<Waitlist />)
    await screen.findByText('pending@example.com')
    expect(screen.getByRole('button', { name: /^Pre-admit$/ })).toBeDisabled()
  })

  it('disables the button when no weekly slots remain', async () => {
    api.waitlistConfig.mockResolvedValue({ enabled: true, weeklyApprovalLimit: 1, admittedThisWeek: 1 })
    render(<Waitlist />)
    await screen.findByText('pending@example.com')
    await userEvent.type(screen.getByLabelText('Email to pre-admit'), 'x@example.com')
    expect(screen.getByRole('button', { name: /^Pre-admit$/ })).toBeDisabled()
    expect(api.preAdmitEmail).not.toHaveBeenCalled()
  })
})
