import { useEffect, useState } from 'react'

// Same width the author app treats as a phone.
export const PHONE_MQ = '(max-width: 767px)'

export function isPhoneLayout() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(PHONE_MQ).matches
    : false
}

export function usePhoneLayout() {
  const [phone, setPhone] = useState(isPhoneLayout)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const mq = window.matchMedia(PHONE_MQ)
    const onChange = (e) => setPhone(e.matches)
    setPhone(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return phone
}
