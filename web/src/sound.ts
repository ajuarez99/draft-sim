import { pronounceable } from './pronounce'

/**
 * Draft-night audio: a spoken announcement for each pick, and a chime when you
 * are on the clock.
 *
 * No library. `speechSynthesis` is built into every browser this app targets,
 * runs offline, and costs nothing -- the paid cloud voices (better, but a
 * network round trip and an API key to proxy) are the upgrade if these turn
 * out not to be good enough. See claude/live-pick-names-and-team-fit.md.
 *
 * <h2>The autoplay rule, which shapes everything here</h2>
 *
 * Browsers refuse both speech and WebAudio until the page has been interacted
 * with. That is why enabling is a button the user clicks rather than a setting
 * that quietly turns itself on: the click IS the permission. `prime()` runs
 * inside that click and speaks a short confirmation, which doubles as proof
 * that audio works on this machine -- a silent toggle that turns green and
 * then never makes a sound is the failure mode worth designing out.
 *
 * The preference survives a reload, but the browser's permission does not:
 * after a fresh load nothing will be audible until the user clicks something,
 * anything, on the page. Nothing here can fix that, and pretending otherwise
 * (a "sound is on" badge that is lying) would be worse than the silence.
 */

const STORAGE_KEY = 'bk.sound.v1'

export function speechSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

export function readSoundPref(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'on'
  } catch {
    // Storage blocked entirely (private window). Off is the safe reading.
    return false
  }
}

export function writeSoundPref(on: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off')
  } catch {
    // The in-memory toggle still works for this page load.
  }
}

/**
 * Speak. Deliberately does NOT cancel what is already speaking: an autopick
 * burst delivers several picks at once and cutting each announcement off with
 * the next would leave only the last one intelligible. The caller decides how
 * many picks are worth announcing (useAnnouncer announces the newest only);
 * this just says what it is given.
 */
export function speak(text: string): void {
  if (!speechSupported()) return
  const utterance = new SpeechSynthesisUtterance(text)
  // Explicit rather than inherited from the OS default voice, which on a
  // machine whose locale isn't English would read an English name through a
  // non-English pronunciation model.
  utterance.lang = 'en-US'
  // A shade quicker than default. A draft announcement is three or four words
  // and the next pick may already be landing; the default rate reads as
  // ponderous next to a board that has moved on.
  utterance.rate = 1.1
  window.speechSynthesis.speak(utterance)
}

/** Silence immediately, including anything still queued -- what "mute" means. */
export function stopSpeaking(): void {
  if (!speechSupported()) return
  window.speechSynthesis.cancel()
}

/** "{manager} takes {player}" -- the whole announcement. */
export function announcement(manager: string, playerName: string): string {
  // No round-and-pick prefix on purpose. Picks arrive in order and the number
  // is on screen a foot away; spending a second and a half of every
  // announcement on "round two, pick four" delays the only part you cannot
  // already see, and reads as filler by the third round.
  return `${manager} takes ${pronounceable(playerName)}`
}

// Created lazily inside a user gesture (see prime) and reused: a browser will
// hand out an AudioContext at any time but leaves it suspended until it has
// been allowed to start, and constructing a fresh one per chime leaks them.
let ctx: AudioContext | null = null

function audioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null
  const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctor) return null
  if (ctx == null) ctx = new Ctor()
  return ctx
}

/**
 * Two quick notes when your pick comes up.
 *
 * Synthesized rather than an audio file: it is a fifth of a second of sine
 * wave, and shipping (and cache-busting, and licensing) an asset for that
 * would cost more than the code does. This is also the piece most likely to be
 * the *only* thing you want on -- it fires when you have looked away, which is
 * exactly when a spoken pick announcement is least useful.
 */
export function chime(): void {
  const audio = audioContext()
  if (audio == null) return
  void audio.resume().catch(() => {})
  const now = audio.currentTime
  // A rising fifth (A5 -> E6). Rising rather than falling because a falling
  // pair is the universal "something went wrong" shape.
  for (const [i, freq] of [880, 1318.5].entries()) {
    const osc = audio.createOscillator()
    const gain = audio.createGain()
    osc.type = 'sine'
    osc.frequency.value = freq
    const start = now + i * 0.12
    // Ramped, not switched: an oscillator started and stopped at full gain
    // clicks at both ends.
    gain.gain.setValueAtTime(0.0001, start)
    gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02)
    gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.16)
    osc.connect(gain).connect(audio.destination)
    osc.start(start)
    osc.stop(start + 0.18)
  }
}

/**
 * Call from the click that turns sound on -- and only from there. Opens the
 * AudioContext and says one short line, both of which need the user gesture
 * this is running inside.
 */
export function prime(): void {
  void audioContext()?.resume().catch(() => {})
  speak('Announcements on')
}
