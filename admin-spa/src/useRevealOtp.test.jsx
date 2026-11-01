import { describe, it, expect, vi } from 'vitest'
import { render } from '@testing-library/react'
import { useRef } from 'react'
import { useRevealOtp } from './useRevealOtp.js'

function Box({ otp }) {
  const ref = useRef(null)
  useRevealOtp(ref, otp)
  return <div ref={ref}><input aria-label="code" /></div>
}

describe('useRevealOtp', () => {
  it('reveals the box again for a new challenge, not on a re-render', () => {
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    const { rerender } = render(<Box otp={null} />)
    expect(scrolled).not.toHaveBeenCalled()
    rerender(<Box otp={{ challengeId: 'a' }} />)
    rerender(<Box otp={{ challengeId: 'a' }} />)
    expect(scrolled).toHaveBeenCalledTimes(1)
    rerender(<Box otp={{ challengeId: 'b' }} />)
    expect(scrolled).toHaveBeenCalledTimes(2)
  })
})
