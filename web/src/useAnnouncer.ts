import { useEffect, useRef } from 'react'
import { announcement, chime, speak, stopSpeaking } from './sound'

type Pick = { pickNo: number; manager: string; player: { name: string } }

/**
 * Speaks each new pick, and chimes when your turn arrives.
 *
 * All of the difficulty here is in NOT announcing things:
 *
 *  - `live.recentPicks` re-delivers the same dozen picks on every state frame,
 *    so anything that reacted to the array itself would read the board out
 *    loud every ten seconds. Only a pick number higher than the last one
 *    spoken counts as new.
 *  - Turning sound on mid-draft must not replay the backlog. The first pass
 *    after enabling seeds the high-water mark and says nothing -- you hear the
 *    NEXT pick, not the last one, which already happened while you were
 *    reading the board.
 *  - StrictMode double-invokes effects in development. The high-water ref
 *    survives the remount, so the second pass is a no-op rather than a second
 *    announcement -- which is the same mechanism the first two rules use, not
 *    a special case for it.
 *  - An autopick burst lands several picks in one tick. Only the newest is
 *    announced: by the time a voice has read four names the room has moved on,
 *    and the three it skipped are on screen.
 *
 * @param yourTurn whether the clock is on the user's own seat. Seeded on
 *   enable like the pick number is, so the chime fires on the transition into
 *   your turn rather than immediately because you happened to switch sound on
 *   while already on the clock -- you are looking at the page in that moment;
 *   the chime is for when you are not.
 */
export function useAnnouncer(enabled: boolean, newest: Pick | null, yourTurn: boolean): void {
  const spokenThroughRef = useRef<number | null>(null)
  const wasYourTurnRef = useRef<boolean | null>(null)

  useEffect(() => {
    if (!enabled) {
      stopSpeaking()
      // Cleared, not kept: re-enabling later must re-seed, or the first thing
      // you would hear is every pick that landed while muted.
      spokenThroughRef.current = null
      return
    }
    const pickNo = newest?.pickNo ?? 0
    if (spokenThroughRef.current == null) {
      spokenThroughRef.current = pickNo
      return
    }
    if (pickNo <= spokenThroughRef.current) return
    spokenThroughRef.current = pickNo
    if (newest) speak(announcement(newest.manager, newest.player.name))
    // newest is compared by pick number, not identity: the object is rebuilt
    // on every state frame and every resimulation, and neither is news.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, newest?.pickNo])

  useEffect(() => {
    if (!enabled) {
      wasYourTurnRef.current = null
      return
    }
    const was = wasYourTurnRef.current
    wasYourTurnRef.current = yourTurn
    if (was === false && yourTurn) chime()
  }, [enabled, yourTurn])
}
