import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const searchUsers = vi.hoisted(() => vi.fn())
const accountState = vi.hoisted(() => vi.fn())
const unsuspend = vi.hoisted(() => vi.fn())
const unfreezeWallet = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())
const setPlatformAdmin = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: (...a) => searchUsers(...a),
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    accountState: (...a) => accountState(...a),
    unsuspend: (...a) => unsuspend(...a),
    unfreezeWallet: (...a) => unfreezeWallet(...a),
    refund: (...a) => refund(...a),
    setPlatformAdmin: (...a) => setPlatformAdmin(...a),
  },
}))

import Users from './Users.jsx'

const HELD = {
  userId: 'u1',
  accountStatus: 'SUSPENDED_FOR_REVIEW',
  suspensionKind: 'DISPUTE',
  walletExists: true,
  balanceCents: 0,
  walletFrozen: true,
  walletFrozenReason: 'dispute:du_1',
  unrecoveredCents: 126,
}

async function manage() {
  render(
    <MemoryRouter>
      <Users />
    </MemoryRouter>,
  )
  await userEvent.type(screen.getByLabelText(/email contains/i), 'a@')
  await userEvent.click(screen.getByRole('button', { name: /^search$/i }))
  await userEvent.click(screen.getByRole('button', { name: /manage/i }))
}

describe('Users — lifting a chargeback or review hold (F3)', () => {
  beforeEach(() => {
    for (const fn of [otpStart, otpVerify, searchUsers, accountState, unsuspend, unfreezeWallet, refund,
      setPlatformAdmin]) {
      fn.mockReset()
    }
    searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'SUSPENDED_FOR_REVIEW' }])
    accountState.mockResolvedValue(HELD)
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    unsuspend.mockResolvedValue({ userId: 'u1', accountStatus: 'ACTIVE' })
    unfreezeWallet.mockResolvedValue({ userId: 'u1', lifted: true })
  })

  it('shows why the user is held, including money a refund could not take back', async () => {
    await manage()

    expect(accountState).toHaveBeenCalledWith('u1')
    expect(await screen.findByText(/suspended for review/i, { selector: '.hold-status' })).toBeInTheDocument()
    expect(screen.getByText(/dispute:du_1/)).toBeInTheDocument()
    expect(screen.getByText(/\$1\.26 could not be taken back/i)).toBeInTheDocument()
    expect(screen.getByText('card dispute')).toBeInTheDocument()
  })

  it('names a content review so it is not mistaken for a payment hold', async () => {
    accountState.mockResolvedValue({ ...HELD, suspensionKind: 'CONTENT', walletFrozen: false })
    await manage()

    expect(await screen.findByText('content review')).toBeInTheDocument()
  })

  it('reactivates with a note, binding no params into the OTP', async () => {
    await manage()
    const reactivate = await screen.findByRole('button', { name: /start otp reactivate account/i })
    expect(reactivate).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(reactivate)
    expect(otpStart).toHaveBeenCalledWith('unsuspend', 'u1', {})

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(otpVerify).toHaveBeenCalledWith('ch-1', '123456', 'unsuspend', 'u1', {})
    expect(unsuspend).toHaveBeenCalledWith('u1', 'dispute won in Stripe', 'tok-abc',
      { acknowledgeWalletUnknown: false, resetContentStrikes: false })
    expect(refund).not.toHaveBeenCalled()
    expect(await screen.findByText(/a@b\.co is ACTIVE again/i)).toBeInTheDocument()
  })

  it('says the account is still held when the reactivation leaves a content review standing', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'SUSPENDED', suspensionKind: 'CONTENT',
      walletFrozen: false })
    unsuspend.mockResolvedValue({ userId: 'u1', accountStatus: 'SUSPENDED_FOR_REVIEW', heldForContentReview: true })
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'staff hold over')
    await userEvent.click(screen.getByRole('button', { name: /start otp reactivate account/i }))
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(await screen.findByText(
      'a@b.co is still held for a content review. Tick “Reset content strikes” and reactivate again to lift it.',
    )).toBeInTheDocument()
    expect(screen.queryByText(/again\.$/)).not.toBeInTheDocument()
    expect(screen.queryByText(/is SUSPENDED_FOR_REVIEW again/i)).not.toBeInTheDocument()
  })

  it('shows a content review pending on a staff suspension', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'SUSPENDED', suspensionKind: 'CONTENT',
      walletFrozen: false })
    await manage()

    expect(await screen.findByText('Content review pending')).toBeInTheDocument()
  })

  it('unfreezes the wallet with the OTP bound to this wallet only', async () => {
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp unfreeze wallet/i }))
    expect(otpStart).toHaveBeenCalledWith('wallet_unfreeze', 'u1', { ownerType: 'USER', ownerId: 'u1' })

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(unfreezeWallet).toHaveBeenCalledWith('u1', 'dispute won in Stripe', 'tok-abc')
    expect(await screen.findByText(/wallet unfrozen for a@b\.co/i)).toBeInTheDocument()
  })

  it('re-reads the hold after a failed lift so a lift that landed is not offered again', async () => {
    unfreezeWallet.mockRejectedValue(new Error('downstream_rejected'))
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp unfreeze wallet/i }))
    accountState.mockResolvedValue({ ...HELD, walletFrozen: false, walletFrozenReason: null })
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(await screen.findByText(/downstream_rejected/)).toBeInTheDocument()
    expect(accountState).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: /unfreeze wallet/i })).not.toBeInTheDocument()
  })

  it('says how long the note must be until it is long enough', async () => {
    await manage()
    await screen.findByLabelText(/Why are you lifting the hold/)
    expect(screen.getByText('At least 8 characters (0/8)')).toBeTruthy()
    await userEvent.type(screen.getByLabelText(/Why are you lifting the hold/), 'won it')
    expect(screen.getByText('At least 8 characters (6/8)')).toBeTruthy()
    await userEvent.type(screen.getByLabelText(/Why are you lifting the hold/), ' in Stripe')
    expect(screen.queryByText(/At least 8 characters/)).toBeNull()
  })

  it('brings the code box into view with the cursor in it on a desktop too', async () => {
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    await manage()
    await userEvent.type(await screen.findByLabelText(/Why are you lifting the hold/), 'dispute won')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP reactivate account/ }))
    const code = await screen.findByLabelText(/OTP code/)
    expect(scrolled).toHaveBeenCalled()
    expect(document.activeElement).toBe(code)
  })

  it('shows the new status in the list after a reactivation', async () => {
    await manage()
    await userEvent.type(await screen.findByLabelText(/Why are you lifting the hold/), 'dispute won')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP reactivate account/ }))
    await userEvent.type(await screen.findByLabelText(/OTP code/), '123456')
    searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' }])
    await userEvent.click(screen.getByRole('button', { name: 'Verify & execute' }))
    expect(await screen.findByText('active')).toBeTruthy()
    expect(screen.getByText(/is ACTIVE again/)).toBeTruthy()
  })

  it('refreshes the search that was run, not what the box holds now', async () => {
    await manage()
    await userEvent.type(await screen.findByLabelText(/Why are you lifting the hold/), 'dispute won')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP reactivate account/ }))
    await userEvent.type(screen.getByLabelText(/email contains/i), 'zzz')
    await userEvent.type(await screen.findByLabelText(/OTP code/), '123456')
    searchUsers.mockClear()
    await userEvent.click(screen.getByRole('button', { name: 'Verify & execute' }))
    await screen.findByText(/is ACTIVE again/)
    expect(searchUsers).toHaveBeenCalledWith('a@')
  })

  it('does not crash when the account status is unknown', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: null })
    await manage()

    expect(await screen.findByText(/unknown/i, { selector: '.hold-status' })).toBeInTheDocument()
  })

  it('still offers reactivation when billing is down, and never says there is no hold', async () => {
    accountState.mockResolvedValue({ ...HELD, walletFrozen: false, walletFrozenReason: null,
      unrecoveredCents: 0, walletDegraded: true })
    await manage()

    expect(await screen.findByText(/wallet: unknown — billing did not answer/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start otp reactivate account/i })).toBeInTheDocument()
    expect(screen.queryByText(/no hold on this account/i)).not.toBeInTheDocument()
  })

  it('does not claim "no hold" for an active account when billing is down', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'ACTIVE', suspensionKind: null,
      walletFrozen: false, walletFrozenReason: null, unrecoveredCents: 0, walletDegraded: true })
    await manage()

    expect(await screen.findByText(/wallet: unknown — billing did not answer/i)).toBeInTheDocument()
    expect(screen.queryByText(/no hold on this account/i)).not.toBeInTheDocument()
  })

  it('offers no lift when nothing is held', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'ACTIVE', walletFrozen: false,
      walletFrozenReason: null, unrecoveredCents: 0 })
    await manage()

    expect(await screen.findByText(/no hold on this account/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate account/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /unfreeze wallet/i })).not.toBeInTheDocument()
  })

  it('says a purged account is purged, not that it has no hold', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'PURGED', walletFrozen: false,
      walletFrozenReason: null, unrecoveredCents: 0 })
    await manage()

    expect(await screen.findByText(/account purged/i)).toBeInTheDocument()
    expect(screen.queryByText(/no hold on this account/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate account/i })).not.toBeInTheDocument()
  })

  it('does not offer reactivation for a banned account', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'BANNED' })
    await manage()

    expect(await screen.findByRole('button', { name: /start otp unfreeze wallet/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate account/i })).not.toBeInTheDocument()
  })

  it('shows the content strikes and resets them only when asked (L5)', async () => {
    accountState.mockResolvedValue({ ...HELD, contentStrikes: 3 })
    await manage()

    expect(await screen.findByText(/content strikes: 3/i)).toBeInTheDocument()
    const reset = screen.getByRole('checkbox', { name: /reset content strikes/i })
    expect(reset).not.toBeChecked()
    await userEvent.click(reset)
    await userEvent.type(screen.getByLabelText(/why are you lifting the hold/i), 'content false positive')
    await userEvent.click(screen.getByRole('button', { name: /start otp reactivate account/i }))
    expect(otpStart).toHaveBeenCalledWith('unsuspend', 'u1', {})
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(unsuspend).toHaveBeenCalledWith('u1', 'content false positive', 'tok-abc',
      { acknowledgeWalletUnknown: false, resetContentStrikes: true })
  })

  it('shows a recorded payment hold under a content review (L3)', async () => {
    accountState.mockResolvedValue({ ...HELD, suspensionKind: 'CONTENT', paymentHoldKind: 'DISPUTE' })
    await manage()

    expect(await screen.findByText(/payment hold recorded/i)).toBeInTheDocument()
    expect(screen.getByText('card dispute')).toBeInTheDocument()
  })

  it('does not show a recorded payment hold once the account is active again', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'ACTIVE', suspensionKind: null,
      paymentHoldKind: 'DISPUTE' })
    await manage()

    expect(await screen.findByText(/wallet frozen/i)).toBeInTheDocument()
    expect(screen.queryByText(/payment hold recorded/i)).not.toBeInTheDocument()
  })

  it('asks before reactivating while billing is down, then sends the acknowledgement', async () => {
    accountState.mockResolvedValue({ ...HELD, walletFrozen: false, walletFrozenReason: null,
      unrecoveredCents: 0, walletDegraded: true })
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp reactivate account/i }))

    expect(otpStart).not.toHaveBeenCalled()
    expect(screen.getByText(
      "Billing did not answer, so we can't tell if the wallet is frozen. Reactivate anyway?")).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /reactivate anyway/i }))
    expect(otpStart).toHaveBeenCalledWith('unsuspend', 'u1', {})
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(unsuspend).toHaveBeenCalledWith('u1', 'dispute won in Stripe', 'tok-abc',
      { acknowledgeWalletUnknown: true, resetContentStrikes: false })
  })

  it('offers the confirm when the reactivation comes back wallet_status_unknown', async () => {
    unsuspend.mockRejectedValueOnce(Object.assign(new Error('Billing did not answer'),
      { status: 409, body: { code: 'wallet_status_unknown' } }))
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp reactivate account/i }))
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(await screen.findByRole('button', { name: /reactivate anyway/i })).toBeInTheDocument()
  })

  it('shows the payment-hold refusal verbatim', async () => {
    const copy = "Unfreeze the wallet first — this account's wallet is frozen by a payment dispute or refund."
    unsuspend.mockRejectedValueOnce(Object.assign(new Error(copy),
      { status: 409, body: { code: 'wallet_frozen_by_payment_hold', message: copy } }))
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp reactivate account/i }))
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(await screen.findByText(copy)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate anyway/i })).not.toBeInTheDocument()
  })

  it('grants platform admin only through an OTP bound to the new value (O-5)', async () => {
    setPlatformAdmin.mockResolvedValue({ userId: 'u1', isPlatformAdmin: true })
    await manage()
    await userEvent.click(await screen.findByRole('button', { name: /promote to platform admin/i }))

    expect(setPlatformAdmin).not.toHaveBeenCalled()
    expect(otpStart).toHaveBeenCalledWith('set_platform_admin', 'u1', { isPlatformAdmin: true })
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(otpVerify).toHaveBeenCalledWith('ch-1', '123456', 'set_platform_admin', 'u1', { isPlatformAdmin: true })
    expect(setPlatformAdmin).toHaveBeenCalledWith('u1', true, 'tok-abc')
  })
})
