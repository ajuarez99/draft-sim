#!/usr/bin/env bash
# diff.sh <dirA> <dirB>   (spec 045 T001, plan A15)
# Compares two capture dirs. On weekly-report files ONLY, the top-level keys live, liveWeek and
# sleeperLeagueId are deleted from both sides first; everything else is compared verbatim.
# Prints each differing file and exits 1 if any differ.
set -u
A="${1:?dirA}"; B="${2:?dirB}"
HERE="$(cd "$(dirname "$0")" && pwd)"
rc=0
files=$( { ls "$A"; ls "$B"; } | sort -u )
for f in $files; do
  if [ ! -e "$A/$f" ] || [ ! -e "$B/$f" ]; then echo "MISSING on one side: $f"; rc=1; continue; fi
  case "$f" in
    weekly-report*.json)
      python "$HERE/norm.py" --drop live,liveWeek,sleeperLeagueId < "$A/$f" > "${TMPDIR:-/tmp}/045a.$$"
      python "$HERE/norm.py" --drop live,liveWeek,sleeperLeagueId < "$B/$f" > "${TMPDIR:-/tmp}/045b.$$"
      if ! cmp -s "${TMPDIR:-/tmp}/045a.$$" "${TMPDIR:-/tmp}/045b.$$"; then echo "DIFF: $f"; diff "${TMPDIR:-/tmp}/045a.$$" "${TMPDIR:-/tmp}/045b.$$" | head -${DIFF_LINES:-20}; rc=1; fi
      rm -f "${TMPDIR:-/tmp}/045a.$$" "${TMPDIR:-/tmp}/045b.$$" ;;
    *)
      if ! cmp -s "$A/$f" "$B/$f"; then echo "DIFF: $f"; diff "$A/$f" "$B/$f" | head -${DIFF_LINES:-20}; rc=1; fi ;;
  esac
done
[ $rc = 0 ] && echo "identical"
exit $rc
