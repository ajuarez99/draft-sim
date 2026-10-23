#!/usr/bin/env bash
# capture.sh <base-url> <out-dir>   (spec 045 T001, plan A15)
# One normalised (sorted-key) JSON file per endpoint, plus a sibling .status with the HTTP code.
# Read-only: GETs only, and psql only SELECTs (stored weeks, a member id per league).
set -u
BASE="${1:?base url}"; OUT="${2:?out dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
export PGPASSWORD=draftsim
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
q() { timeout 30 psql -w -h localhost -p 5433 -U draftsim -d draftsim -At -c "$1" | tr -d '\r'; }

NBA24=1141438340626231296; NBA25=1229352720222134272; NBA26=1339351318115946496
NFL25A=1254190892974084096; NFL25B=1262506916429430784
NFL26A=1389361939561332736; NFL26B=1346366555759341568; NFL26C=1391509063170293760
WEEKLY="$NBA24 $NBA25 $NBA26 $NFL25A $NFL25B $NFL26A $NFL26B $NFL26C"
PAGES="$NBA26 $NFL26C"

n=0
get() { # name path
  local name="$1" path="$2" code resp NL=$'\n'
  resp=$(curl -s -m 120 -w "${NL}%{http_code}" -H "X-Sleeper-User: $USERID" "$BASE$path")
  code="${resp##*$NL}"
  printf '%s' "${resp%$NL*}" | python "$HERE/norm.py" > "$OUT/$name.json"
  echo "$code" > "$OUT/$name.status"
  n=$((n+1))
  [ "$code" = 200 ] || echo "non-200: $code $path" >&2
}

for L in $WEEKLY; do
  USERID=$(q "select m.sleeper_user_id from league l join league_member lm on lm.league_id=l.id join manager m on m.id=lm.manager_id where l.sleeper_id='$L' order by lm.id limit 1")
  weeks=$(q "select distinct w.week from roster_week_points w join league l on l.id=w.league_id where l.sleeper_id='$L' order by 1")
  get "weekly-report_${L}_w0" "/api/leagues/$L/weekly-report/0"
  for w in $weeks; do get "weekly-report_${L}_w${w}" "/api/leagues/$L/weekly-report/$w"; done
done

for L in $PAGES; do
  USERID=$(q "select m.sleeper_user_id from league l join league_member lm on lm.league_id=l.id join manager m on m.id=lm.manager_id where l.sleeper_id='$L' order by lm.id limit 1")
  # playoff odds ride on /power and /history (no standalone route); /forecast and /analysis are the other season readers
  for ep in expected-wins roster-management superlatives refresh power history forecast analysis; do
    get "${ep}_${L}" "/api/leagues/$L/$ep"
  done
  get "recap_${L}_w1" "/api/leagues/$L/recap/1"
done

# lock-ins on NBA 2025: default, then one player taken from the response
USERID=$(q "select m.sleeper_user_id from league l join league_member lm on lm.league_id=l.id join manager m on m.id=lm.manager_id where l.sleeper_id='$NBA25' order by lm.id limit 1")
get "lock-ins_${NBA25}" "/api/leagues/$NBA25/lock-ins"
PID=$(python -X utf8 -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); p=d.get('players') or []; print(max(p,key=lambda x:(x.get('rodeOut') or 0))['key'] if p else '')" "$OUT/lock-ins_${NBA25}.json" 2>/dev/null)
echo "lock-ins player param: '$PID'" >&2
if [ -n "$PID" ]; then get "lock-ins_${NBA25}_player" "/api/leagues/$NBA25/lock-ins?player=$PID"; echo "$PID" > "$OUT/lock-ins-player-id.txt"; fi
echo "captured $n endpoints into $OUT"
