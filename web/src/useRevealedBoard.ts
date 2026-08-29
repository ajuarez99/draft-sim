import { useEffect, useRef, useState } from 'react'
import type { PredictedPick } from './api'

const EMPTY_BOARD: PredictedPick[] = []

export function useRevealedBoard(
  board: PredictedPick[] | undefined,
  maxPickNo: number,
  opts?: { durationMs?: number; tickMs?: number },
) {
  const b = board ?? EMPTY_BOARD
  const [revealedThrough, setRevealedThrough] = useState(0)
  const [isRevealing, setIsRevealing] = useState(false)
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    if (maxPickNo <= 0) {
      setRevealedThrough(0)
      setIsRevealing(false)
      return
    }
    setRevealedThrough(0)
    setIsRevealing(true)
    const durationMs = opts?.durationMs ?? 4000
    const tickMs = opts?.tickMs ?? 60
    const ticks = Math.max(1, Math.round(durationMs / tickMs))
    const perTick = Math.max(1, Math.ceil(maxPickNo / ticks))
    let current = 0
    const id = window.setInterval(() => {
      current = Math.min(maxPickNo, current + perTick)
      setRevealedThrough(current)
      if (current >= maxPickNo) {
        window.clearInterval(id)
        setIsRevealing(false)
      }
    }, tickMs)
    timerRef.current = id
    return () => window.clearInterval(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [b, maxPickNo])

  function skip() {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    setRevealedThrough(maxPickNo)
    setIsRevealing(false)
  }

  function scrubTo(pickNo: number) {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    setIsRevealing(false)
    setRevealedThrough(Math.max(0, Math.min(maxPickNo, pickNo)))
  }

  return { revealedThrough, isRevealing, skip, scrubTo }
}
