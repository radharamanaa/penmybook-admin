import { useEffect } from 'react'

// The code box opens far below the button that asked for it, so it comes to the operator: on every
// layout it scrolls into view with the cursor in it. Only the first input: the row's other controls
// are buttons.
export function useRevealOtp(rowRef, otp) {
  const challengeId = otp?.challengeId // a new challenge reuses the same box: reveal it again
  useEffect(() => {
    if (!challengeId) return
    rowRef.current?.scrollIntoView?.({ block: 'center' })
    rowRef.current?.querySelector('input')?.focus({ preventScroll: true })
  }, [challengeId, rowRef])
}
