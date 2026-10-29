import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())
const searchUsers = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: (...a) => searchUsers(...a),
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    refund: (...a) => refund(...a),
    accountState: async () => ({ accountStatus: 'ACTIVE', walletFrozen: false, unrecoveredCents: 0 }),
  },
}))

import Users from './Users.jsx'

const REQUEST_ID = '22222222-2222-2222-2222-222222222222'
const PAYMENT_ID = '11111111-1111-1111-1111-111111111111'

function renderUsers(search = '') {
  return render(
    <MemoryRouter initialEntries={[`/users${search}`]}>
      <Users />
    </MemoryRouter>,
  )
}

describe('Users refundRequestId (U4 / S7)', () => {
  beforeEach(() => {
    searchUsers.mockReset()
    otpStart.mockReset()
    otpVerify.mockReset()
    refund.mockReset()
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    refund.mockResolvedValue({})
  })

  it('keeps the deep-linked request id read-only and names it on OTP + payout', async () => {
    renderUsers(
      `?refundRequestId=${REQUEST_ID}&ownerType=USER&ownerId=u1&ownerEmail=a@b.co&paymentId=${PAYMENT_ID}`,
    )

    const requestField = screen.getByLabelText(/refund request id/i)
    expect(requestField).toHaveValue(REQUEST_ID)
    expect(requestField).toHaveAttribute('readOnly')
    expect(screen.getByLabelText(/refund — payment id/i)).toHaveValue(PAYMENT_ID)

    const start = screen.getByRole('button', { name: /start otp refund/i })
    expect(start).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/refund amount \(cents\)/i), '2500')
    await userEvent.click(start)

    expect(otpStart).toHaveBeenCalledWith('refund', 'u1', {
      paymentId: PAYMENT_ID,
      refundAmountCents: 2500,
      refundRequestId: REQUEST_ID,
    })

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(refund).toHaveBeenCalledWith(
      'u1', PAYMENT_ID, 2500, 'admin refund', 'tok-abc', REQUEST_ID,
    )
  })

  it('does not mint a user from an ORG payout deep-link', () => {
    renderUsers(
      `?refundRequestId=${REQUEST_ID}&ownerType=ORG&ownerId=org-1&ownerEmail=org@b.co&paymentId=${PAYMENT_ID}`,
    )
    expect(screen.getByRole('status')).toHaveTextContent(/org payout is not wired/i)
    expect(screen.getByRole('status')).toHaveTextContent(REQUEST_ID)
    expect(screen.queryByRole('button', { name: /start otp refund/i })).toBeNull()
  })

  it('omits refundRequestId on a bare payout when no request is named', async () => {
    searchUsers.mockResolvedValue([
      { userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' },
    ])
    renderUsers()
    await userEvent.type(screen.getByLabelText(/email contains/i), 'a@')
    await userEvent.click(screen.getByRole('button', { name: /^search$/i }))
    await userEvent.click(screen.getByRole('button', { name: /manage/i }))

    await userEvent.type(screen.getByLabelText(/refund — payment id/i), PAYMENT_ID)
    await userEvent.type(screen.getByLabelText(/refund amount \(cents\)/i), '2500')
    await userEvent.click(screen.getByRole('button', { name: /start otp refund/i }))

    expect(otpStart).toHaveBeenCalledWith('refund', 'u1', {
      paymentId: PAYMENT_ID,
      refundAmountCents: 2500,
    })
  })
})
