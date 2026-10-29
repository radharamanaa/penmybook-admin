import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())
const forceResolveReversed = vi.hoisted(() => vi.fn())
const searchUsers = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: (...a) => searchUsers(...a),
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    refund: (...a) => refund(...a),
    forceResolveReversed: (...a) => forceResolveReversed(...a),
    accountState: async () => ({ accountStatus: 'ACTIVE', walletFrozen: false, unrecoveredCents: 0 }),
  },
}))

import Users from './Users.jsx'

const PAYMENT_ID = '11111111-1111-1111-1111-111111111111'

describe('Users force-resolve reversed (C-17)', () => {
  beforeEach(() => {
    searchUsers.mockReset()
    otpStart.mockReset()
    otpVerify.mockReset()
    refund.mockReset()
    forceResolveReversed.mockReset()
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    forceResolveReversed.mockResolvedValue({ paymentId: PAYMENT_ID, restoredCents: 2500 })
    searchUsers.mockResolvedValue([
      { userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' },
    ])
  })

  it('starts a refund OTP with forceResolve so a payout token cannot match', async () => {
    render(
      <MemoryRouter>
        <Users />
      </MemoryRouter>,
    )
    await userEvent.type(screen.getByLabelText(/email contains/i), 'a@')
    await userEvent.click(screen.getByRole('button', { name: /^search$/i }))
    await userEvent.click(screen.getByRole('button', { name: /manage/i }))

    expect(screen.getByText(/does not credit the card/i)).toBeInTheDocument()
    expect(screen.getByText(/a failed refund is already returned/i)).toBeInTheDocument()
    expect(screen.getByText(/webhooks never auto-restore/i)).toBeInTheDocument()

    const restore = screen.getByRole('button', { name: /start otp restore \(reversed\)/i })
    expect(restore).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/restore after stripe reversed/i), 'short')
    expect(restore).toBeDisabled()

    await userEvent.clear(screen.getByLabelText(/restore after stripe reversed/i))
    await userEvent.type(screen.getByLabelText(/refund — payment id/i), PAYMENT_ID)
    await userEvent.type(
      screen.getByLabelText(/restore after stripe reversed/i),
      're_01 confirmed reversed',
    )
    await userEvent.click(restore)

    expect(otpStart).toHaveBeenCalledWith('refund', 'u1', {
      paymentId: PAYMENT_ID,
      forceResolve: true,
      note: 're_01 confirmed reversed',
    })

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(forceResolveReversed).toHaveBeenCalledWith(
      'u1', PAYMENT_ID, 're_01 confirmed reversed', 'tok-abc',
    )
    expect(refund).not.toHaveBeenCalled()
    expect(await screen.findByText(/restored 2500¢ to wallet/i)).toBeInTheDocument()
  })
})
