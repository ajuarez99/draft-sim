import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Vitest doesn't put `afterEach` in global scope by default, and RTL's own
// automatic cleanup relies on finding it there -- without this, each test's
// render() accumulates in the same jsdom document instead of resetting.
afterEach(() => {
  cleanup()
})

// jsdom implements no layout, so it ships no scrollIntoView at all -- calling
// it throws rather than being a no-op. StartMockModal calls it to keep a
// seeded league visible in its 220px scrolling list. A no-op stub is the
// honest stand-in: there is no scrolling to assert on in jsdom either way, and
// the alternative is a component guarding a browser API that always exists in
// the browser.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

// Same story for pointer capture: jsdom ships the PointerEvent constructor but
// neither setPointerCapture nor releasePointerCapture, so any drag in
// RankBoard throws on the frame the threshold is crossed. Capture is a real
// browser behaviour with nothing to stand in for it here -- what these stubs
// buy is the ability to assert which gestures START a drag at all, which is
// where the touch-vs-scroll decision lives. Where the chip LANDS still cannot
// be tested in jsdom (no layout, so getBoundingClientRect is all zeros); that
// arithmetic lives in dragGesture.ts and is unit-tested directly.
if (!Element.prototype.setPointerCapture) {
  Element.prototype.setPointerCapture = () => {}
}
if (!Element.prototype.releasePointerCapture) {
  Element.prototype.releasePointerCapture = () => {}
}
