import { describe, it, expect } from 'vitest'
import { refundSuccessCopy } from './RefundRetry.jsx'

const PAYMENT_ID = '11111111-1111-1111-1111-111111111111'
// A staff refund typed by hand, with no customer refund request behind it: nothing is closed.
const directRequest = { paymentId: PAYMENT_ID, amountCents: 874, refundRequestId: '' }

describe('refundSuccessCopy without a linked refund request', () => {
  it('names the paid amount on a full payout', () => {
    expect(refundSuccessCopy({ requestedCents: 874, walletDebitedCents: 874 }, directRequest))
      .toBe(`Refund issued for payment ${PAYMENT_ID}: 874¢ (plus any tax refunded with it).`)
  })

  it('names paid, asked and the payment on a shortfall and claims no request closed', () => {
    expect(refundSuccessCopy({ requestedCents: 874, walletDebitedCents: 500 }, directRequest))
      .toBe(`Refunded 500¢ of the 874¢ asked for payment ${PAYMENT_ID} (plus any tax refunded `
        + 'with it) — the rest was already spent.')
  })

  it('says nothing was refunded when the ask was all spent', () => {
    expect(refundSuccessCopy({ requestedCents: 874, walletDebitedCents: 0 }, directRequest))
      .toBe(`Nothing was refunded for payment ${PAYMENT_ID} — the 874¢ asked was already spent.`)
  })
})
