/**
 * Respelling table for the spoken pick announcement (sound.ts).
 *
 * The plumbing for speech is trivial; this file is the part that decides
 * whether it sounds good. Fantasy rosters are close to a worst case for the
 * OS voices the Web Speech API hands us -- apostrophes mid-name, invented
 * spellings, and surnames the English rules mispronounce -- and a voice that
 * mangles "Ja'Marr" is funny once and irritating by round three.
 *
 * These are best-effort phonetic respellings, not IPA: the Web Speech API has
 * no reliable SSML support, so the only lever is the literal text handed to
 * the utterance. Judge them by ear and change them freely -- a respelling that
 * sounds wrong on your machine's voice IS wrong, because that is the only
 * place it will ever be heard. Nothing else reads this table.
 *
 * It is deliberately short. Every entry is a name observed to need help, not
 * every name that looks unusual; a table that tries to cover the whole player
 * pool would be wrong in more places than it was right.
 */
const RESPELLINGS: Record<string, string> = {
  'jamarr chase': 'juh-MAR Chase',
  'amon-ra st brown': 'AH-mon RAH Saint Brown',
  'puka nacua': 'POO-kuh nah-KOO-uh',
  'bijan robinson': 'BEE-zhon Robinson',
  'devon achane': 'day-VON uh-SHAWN',
  'rachaad white': 'ruh-SHAWD White',
  'jahmyr gibbs': 'juh-MEER Gibbs',
  'kyren williams': 'KY-ren Williams',
  'breece hall': 'BREESE Hall',
  'chris olave': 'Chris oh-LAH-vay',
  'devonta smith': 'duh-VON-tay Smith',
  'ceedee lamb': 'See-Dee Lamb',
  'tyreek hill': 'ty-REEK Hill',
  'xavier worthy': 'ZAY-vee-er Worthy',
  'sam laporta': 'Sam luh-POR-tuh',
  'rome odunze': 'Rome oh-DUN-zay',
  'jaxon smith-njigba': 'Jackson Smith en-JIG-buh',
  'travis etienne': 'Travis ET-ee-en',
  'najee harris': 'NAH-jee Harris',
  'dandre swift': 'DAN-dray Swift',
  'isiah pacheco': 'Isiah pah-CHECK-oh',
  'nico collins': 'NEE-ko Collins',
  'jayden daniels': 'JAY-den Daniels',
  'kenneth walker': 'Kenneth Walker',
}

// Read as letters ("I I I") or as a number by different voices, and neither is
// what a listener wants -- the surname alone identifies the player out loud.
const SUFFIXES = new Set(['jr', 'sr', 'ii', 'iii', 'iv', 'v'])

/**
 * The lookup key: lowercased, with the punctuation that varies between
 * Sleeper's spelling and a table entry's flattened out. "Ja'Marr Chase",
 * "JaMarr Chase" and "ja marr chase" all have to find the same row, because
 * which one arrives depends on a feed nobody here controls.
 */
function key(name: string): string {
  return name
    .toLowerCase()
    .replace(/[.'’`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * What to hand a SpeechSynthesisUtterance for this player.
 *
 * Falls through to the name as written, which is the right default: most names
 * are read correctly, and a generic "fix" applied to all of them would break
 * far more than the handful this table covers.
 */
export function pronounceable(name: string): string {
  const stripped = name
    .trim()
    .split(/\s+/)
    .filter((t) => !SUFFIXES.has(t.toLowerCase().replace(/[.]/g, '')))
    .join(' ')
  // An all-suffix or empty name would announce silence; say the original
  // rather than nothing.
  const base = stripped.length > 0 ? stripped : name.trim()
  return RESPELLINGS[key(base)] ?? base
}
