const STORAGE_KEY = 'bk.pickCards.v1'

/**
 * Whether the live room pops a card for each landed pick. Default ON, unlike
 * sound (sound.ts): a card makes no noise and needs no browser permission, so
 * an absent key means "never chose", not "chose no". Same try/catch discipline
 * as readSoundPref -- a blocked store reads as the default.
 */
export function readPickCardsPref(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== 'off'
  } catch {
    return true
  }
}

export function writePickCardsPref(on: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off')
  } catch {
    // The in-memory toggle still works for this page load.
  }
}
