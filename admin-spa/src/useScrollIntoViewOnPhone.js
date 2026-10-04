import { useEffect } from 'react'
import { isPhoneLayout } from './usePhoneLayout.js'

// A confirm or OTP panel opens far below the row that was tapped; on a phone, bring it into view.
export function useScrollIntoViewOnPhone(ref, active) {
  useEffect(() => {
    if (!active || !isPhoneLayout()) return
    ref.current?.scrollIntoView?.({ block: 'center' })
  }, [active, ref])
}
