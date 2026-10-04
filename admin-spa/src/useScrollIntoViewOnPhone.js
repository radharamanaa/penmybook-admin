import { useEffect } from 'react'
import { isPhoneLayout } from './usePhoneLayout.js'

// A confirm or OTP panel opens far below the row that was tapped; on a phone, bring it into view.
// `block: 'start'` suits a panel taller than the screen, so its heading stays visible.
export function useScrollIntoViewOnPhone(ref, active, block = 'center') {
  useEffect(() => {
    if (!active || !isPhoneLayout()) return
    ref.current?.scrollIntoView?.({ block })
  }, [active, ref, block])
}
