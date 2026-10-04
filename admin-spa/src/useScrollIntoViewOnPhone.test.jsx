import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import { useRef } from 'react'
import { stubPhoneLayout } from './test/phoneLayout.js'
import { useScrollIntoViewOnPhone } from './useScrollIntoViewOnPhone.js'

function Probe({ active }) {
  const ref = useRef(null)
  useScrollIntoViewOnPhone(ref, active)
  return active ? <div ref={ref}>panel</div> : null
}

beforeEach(() => { Element.prototype.scrollIntoView = vi.fn() })

describe('useScrollIntoViewOnPhone', () => {
  it('scrolls the panel into view on a phone when it opens', () => {
    stubPhoneLayout(true)
    const { rerender } = render(<Probe active={null} />)
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()
    rerender(<Probe active={{ id: 1 }} />)
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'center' })
  })

  it('never scrolls on desktop', () => {
    stubPhoneLayout(false)
    const { rerender } = render(<Probe active={null} />)
    rerender(<Probe active={{ id: 1 }} />)
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()
  })
})
