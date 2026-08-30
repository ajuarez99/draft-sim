import { useEffect, useRef, useState } from 'react'
import type { PredictedPick } from './api'

const EMPTY_PICKS: number[] = []

export function useRevealedBoard(
  // Not read internally -- the reset effect keys on `resetKey`, not board
  // identity (see below), and every consumer reads `board` fresh off `result`
  // on render. Kept (prefixed, unused) in the signature so callers pass the
  // same inputs they always have and so a future reader can still tell what
  // this hook is conceptually a view over.
  _board: PredictedPick[] | undefined,
  maxPickNo: number,
  myPicks: number[] | undefined,
  resetKey: number,
  opts?: { tickMs?: number },
) {
  const mine = myPicks ?? EMPTY_PICKS
  const [revealedThrough, setRevealedThrough] = useState(0)
  const [isRevealing, setIsRevealing] = useState(false)
  const [pausedAt, setPausedAt] = useState<number | null>(null)
  const timerRef = useRef<number | null>(null)

  // Starts a fresh interval, one pick per tick, stopping (and recording a
  // pause) the moment a revealed pick is one of ours. Shared by the initial
  // effect below and resume() -- both just differ in where they start from.
  function startTicking() {
    // 450ms: fast enough that a 14-pick round between your turns is ~6s (not
    // an instant blur), slow enough to actually read as a sequence of picks
    // happening rather than a slog. Retune deliberately, not by guessing.
    const tickMs = opts?.tickMs ?? 450
    const id = window.setInterval(() => {
      setRevealedThrough((prev) => {
        const next = Math.min(maxPickNo, prev + 1)
        if (mine.includes(next)) {
          window.clearInterval(id)
          setIsRevealing(false)
          setPausedAt(next)
        } else if (next >= maxPickNo) {
          window.clearInterval(id)
          setIsRevealing(false)
        }
        return next
      })
    }, tickMs)
    timerRef.current = id
  }

  useEffect(() => {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    setPausedAt(null)
    if (maxPickNo <= 0) {
      setRevealedThrough(0)
      setIsRevealing(false)
      return
    }
    setRevealedThrough(0)
    setIsRevealing(true)
    startTicking()
    return () => {
      if (timerRef.current != null) window.clearInterval(timerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey])

  function resume() {
    if (pausedAt == null) return
    setPausedAt(null)
    if (revealedThrough >= maxPickNo) return
    setIsRevealing(true)
    startTicking()
  }

  function skip() {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    setRevealedThrough(maxPickNo)
    setIsRevealing(false)
    setPausedAt(null)
  }

  function scrubTo(pickNo: number) {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
    setIsRevealing(false)
    setPausedAt(null)
    setRevealedThrough(Math.max(0, Math.min(maxPickNo, pickNo)))
  }

  return { revealedThrough, isRevealing, pausedAt, resume, skip, scrubTo }
}
