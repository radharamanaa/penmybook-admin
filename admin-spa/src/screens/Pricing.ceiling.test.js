import { describe, expect, it } from 'vitest'
import { ceilingError } from './Pricing.jsx'

const ROWS = [
  { value: 'PUBLISH_EPUB', label: 'Publish — EPUB' },
  { value: 'PUBLISH_LINKEDIN', label: 'Publish — LinkedIn PDF ($0–$10)', maxCents: 1000 },
]

describe('LinkedIn PDF price ceiling (owner, 5 Nov 2026: $0–$10)', () => {
  it('allows $0 and $10', () => {
    expect(ceilingError(ROWS, { PUBLISH_LINKEDIN: '0' })).toBeNull()
    expect(ceilingError(ROWS, { PUBLISH_LINKEDIN: '1000' })).toBeNull()
  })
  it('refuses more than $10 with a clear message', () => {
    expect(ceilingError(ROWS, { PUBLISH_LINKEDIN: '1001' })).toBe(
      'Publish — LinkedIn PDF ($0–$10): the highest allowed price is $10.00.',
    )
  })
  it('leaves rows without a ceiling alone', () => {
    expect(ceilingError(ROWS, { PUBLISH_EPUB: '99999' })).toBeNull()
  })
})
