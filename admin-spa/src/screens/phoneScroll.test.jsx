// On a phone, money actions move the viewport to what changed: the Manage
// heading, the OTP row, and the result banner (which sits at the page top).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { stubPhoneLayout } from '../test/phoneLayout.js'

vi.mock('../api.js', () => ({
  api: {
    searchUsers: vi.fn(), otpStart: vi.fn(), otpVerify: vi.fn(), walletTopUp: vi.fn(),
    refundRequestQueue: vi.fn(), refundResiduals: vi.fn(),
  },
}))

import Users from './Users.jsx'
import RefundRequests from './RefundRequests.jsx'

let scrolled
beforeEach(() => {
  scrolled = []
  Element.prototype.scrollIntoView = vi.fn(function (opts) { scrolled.push([this, opts]) })
  stubPhoneLayout(true)
})

const scrolledTo = (el) => scrolled.filter(([target]) => target === el).map(([, opts]) => opts)

async function openManage(api) {
  api.searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' }])
  render(<MemoryRouter><Users /></MemoryRouter>)
  await userEvent.type(screen.getByLabelText(/Email contains/i), 'a@')
  await userEvent.click(screen.getByRole('button', { name: /^Search$/i }))
  await userEvent.click(screen.getByRole('button', { name: /Manage/i }))
}

describe('phone scroll targets', () => {
  it('lands the Users panel at its heading, not its middle', async () => {
    const { api } = await import('../api.js')
    await openManage(api)
    const panel = screen.getByRole('heading', { name: /Manage/ }).closest('.panel').parentElement
    expect(scrolledTo(panel)).toEqual([{ block: 'start' }])
  })

  it('brings the OTP row into view when a code is requested', async () => {
    const { api } = await import('../api.js')
    api.otpStart.mockResolvedValue({ challengeId: 'c1' })
    await openManage(api)
    await userEvent.type(screen.getAllByRole('spinbutton')[0], '100')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP top-up/i }))
    const otpRow = (await screen.findByLabelText(/OTP code/i)).closest('.row')
    expect(scrolledTo(otpRow)).toEqual([{ block: 'center' }])
  })

  it('shows the Users result banner, every time, even when the message repeats', async () => {
    const { api } = await import('../api.js')
    api.otpStart.mockResolvedValue({ challengeId: 'c1' })
    api.otpVerify.mockResolvedValue({ verified: false, failureReason: 'invalid' })
    await openManage(api)
    await userEvent.type(screen.getAllByRole('spinbutton')[0], '100')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP top-up/i }))
    await userEvent.type(await screen.findByLabelText(/OTP code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /Verify & execute/i }))
    const banner = (await screen.findByText(/Code rejected/)).parentElement
    expect(scrolledTo(banner)).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: /Verify & execute/i }))
    expect(scrolledTo(banner)).toHaveLength(2)
  })

  it('shows the Refund requests banner', async () => {
    const { api } = await import('../api.js')
    api.refundRequestQueue.mockRejectedValue(new Error('billing down'))
    api.refundResiduals.mockResolvedValue({ residuals: [], degraded: false })
    render(<MemoryRouter><RefundRequests /></MemoryRouter>)
    const banner = (await screen.findByText(/billing down/)).parentElement
    expect(scrolledTo(banner).length).toBeGreaterThan(0)
  })
})
