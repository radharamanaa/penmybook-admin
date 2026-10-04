import { renderHook, act } from '@testing-library/react'
import { stubPhoneLayout } from './test/phoneLayout.js'
import { usePhoneLayout, isPhoneLayout } from './usePhoneLayout.js'

describe('usePhoneLayout', () => {
  it('reads the phone query on first render', () => {
    stubPhoneLayout(true)
    const { result } = renderHook(() => usePhoneLayout())
    expect(result.current).toBe(true)
  })

  it('follows a viewport change', () => {
    const vp = stubPhoneLayout(false)
    const { result } = renderHook(() => usePhoneLayout())
    expect(result.current).toBe(false)
    act(() => vp.setMatches(true))
    expect(result.current).toBe(true)
  })

  it('is false when matchMedia is missing', () => {
    const saved = window.matchMedia
    window.matchMedia = undefined
    try {
      expect(isPhoneLayout()).toBe(false)
      const { result } = renderHook(() => usePhoneLayout())
      expect(result.current).toBe(false)
    } finally {
      window.matchMedia = saved
    }
  })
})
