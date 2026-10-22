#!/usr/bin/env bash
# One repetition of the spec 041 baseline profiles (plan D1'), on two containers started together:
#   bk041-<tag>-jre  (port 8098)  image $JRE_IMAGE   -> container MB (docker stats), no jcmd
#   bk041-<tag>-jdk  (port 8099)  image $JDK_IMAGE   -> heap + NMT via jcmd
# Both get the same load at the same time, except `worst`, which runs one container after the other
# (its player ingest writes to draftsim_041 and the real Sleeper API is called).
# Usage: baseline-rep.sh <rep-number> [realistic-minutes=10]
# Env: JRE_IMAGE=bk041:main JDK_IMAGE=bk041:main-jdk TAG=main OUT=<rows file> RUNDIR=<dir for csv/samples>
set -u
JRE_EXTRA=${JRE_EXTRA:-}; JDK_EXTRA=${JDK_EXTRA:-}
REP=${1:?rep number}; RMIN=${2:-10}
HERE=$(cd "$(dirname "$0")" && pwd)
JRE_IMAGE=${JRE_IMAGE:-bk041:main}; JDK_IMAGE=${JDK_IMAGE:-bk041:main-jdk}; TAG=${TAG:-main}
RUNDIR=${RUNDIR:-/c/Users/allan/AppData/Local/Temp/claude/C--Users-allan-source-draft-sim/7119a8a6-2a14-49c1-aff7-f36cd6ecaf2e/scratchpad/runs/$TAG}/rep$REP; mkdir -p "$RUNDIR"
export OUT=${OUT:-$HERE/../../specs/041-memory-footprint/measure-rows.md}
JRE=bk041-$TAG-jre; JDK=bk041-$TAG-jdk
M=$HERE/measure.sh
DRAFT=1339351318128517120
MEMBERS=$(docker exec draftsim-pg psql -U draftsim -d draftsim_041 -Atc "select string_agg(m.sleeper_user_id,',' order by lm.id) from league_member lm join manager m on m.id=lm.manager_id where lm.league_id=210")
LOAD="python -I $HERE/load.py --draft $DRAFT --members $MEMBERS --startstate-file $HERE/startstate-nba.json"
snap() { # container profile note
  local nm=$1; shift
  "$M" snap "$nm" "$1" "rep=$REP ${2:-}"
}
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$RUNDIR/log.txt"; }

log "rep $REP start ($JRE_IMAGE / $JDK_IMAGE)"
"$M" start $JRE "$JRE_IMAGE" 8098 "$JRE_EXTRA" >/dev/null
"$M" start $JDK "$JDK_IMAGE" 8099 "-XX:NativeMemoryTracking=summary $JDK_EXTRA" >/dev/null
"$M" wait 8098 && "$M" wait 8099 || exit 1
log "ready; rest for 60 s"; sleep 60
snap $JRE "rest" "image=$JRE_IMAGE"; snap $JDK "rest" "image=$JDK_IMAGE"

log "warm sweep"
"$M" warm 8098 > "$RUNDIR/warm-jre.txt" 2>&1 & "$M" warm 8099 > "$RUNDIR/warm-jdk.txt" 2>&1 & wait
cat "$RUNDIR/warm-jre.txt" "$RUNDIR/warm-jdk.txt" | tail -4; sleep 15
snap $JRE "warm" "image=$JRE_IMAGE"; snap $JDK "warm" "image=$JDK_IMAGE"

samplers=()
startsamp() { # tag
  : > "$RUNDIR/$1-jre.mb"; : > "$RUNDIR/$1-jdk.mb"; : > "$RUNDIR/$1-jdk.heap"
  "$M" sample $JRE "$RUNDIR/$1-jre.mb" & samplers+=($!)
  "$M" sample $JDK "$RUNDIR/$1-jdk.mb" & samplers+=($!)
  "$M" hsample $JDK "$RUNDIR/$1-jdk.heap" & samplers+=($!)
}
stopsamp() { for f in "$RUNDIR"/*.mb "$RUNDIR"/*.heap; do touch "$f.stop"; done; sleep 4; kill "${samplers[@]}" 2>/dev/null; samplers=(); }
heappeak() { awk '$2>c{c=$2} $3>u{u=$3} END{printf "heap committed peak %.0f MB, used peak %.0f MB (used includes garbage)", c, u}' "$1"; }

log "realistic: $RMIN min, 1 call / 30 s, 500 iterations"
startsamp realistic
$LOAD --base http://localhost:8098 --mode realistic --iterations 500 --interval 30 --duration $((RMIN*60)) --out "$RUNDIR/realistic-jre.csv" > "$RUNDIR/realistic-jre.txt" 2>&1 &
L1=$!
$LOAD --base http://localhost:8099 --mode realistic --iterations 500 --interval 30 --duration $((RMIN*60)) --out "$RUNDIR/realistic-jdk.csv" > "$RUNDIR/realistic-jdk.txt" 2>&1 &
L2=$!
wait $L1 $L2
stopsamp
NOGC=1 snap $JRE "realistic-end" "image=$JRE_IMAGE peak=$("$M" peak "$RUNDIR/realistic-jre.mb")MB $(cat "$RUNDIR/realistic-jre.txt")"
NOGC=1 snap $JDK "realistic-end" "image=$JDK_IMAGE peak=$("$M" peak "$RUNDIR/realistic-jdk.mb")MB; $(heappeak "$RUNDIR/realistic-jdk.heap"); $(cat "$RUNDIR/realistic-jdk.txt")"

log "stress: 12 members x 5 rounds x 500 it, concurrent in a round"
startsamp stress; T0=$(date +%s)
$LOAD --base http://localhost:8098 --mode stress --rounds 5 --iterations 500 --out "$RUNDIR/stress-jre.csv" > "$RUNDIR/stress-jre.txt" 2>&1 &
L1=$!
$LOAD --base http://localhost:8099 --mode stress --rounds 5 --iterations 500 --out "$RUNDIR/stress-jdk.csv" > "$RUNDIR/stress-jdk.txt" 2>&1 &
L2=$!
wait $L1 $L2
stopsamp
NOGC=1 snap $JRE "stress-end" "image=$JRE_IMAGE peak=$("$M" peak "$RUNDIR/stress-jre.mb")MB $(cat "$RUNDIR/stress-jre.txt")"
NOGC=1 snap $JDK "stress-end" "image=$JDK_IMAGE peak=$("$M" peak "$RUNDIR/stress-jdk.mb")MB; $(heappeak "$RUNDIR/stress-jdk.heap"); $(cat "$RUNDIR/stress-jdk.txt")"

log "recovery: +1 / +5 / +10 min, no GC forced (the current JVM settings on their own)"
TS=$(date +%s)
for mins in 1 5 10; do
  tgt=$((TS + mins*60)); now=$(date +%s); [ $tgt -gt $now ] && sleep $((tgt-now))
  NOGC=1 snap $JRE "recovery+${mins}min" "image=$JRE_IMAGE"; NOGC=1 snap $JDK "recovery+${mins}min" "image=$JDK_IMAGE"
done
snap $JDK "recovery+10min-afterGC" "image=$JDK_IMAGE (GC.run forced, live set)"

worst() { # container port imagelabel
  local nm=$1 port=$2 jdk=$3
  log "worst on $nm: 2 x 5000-iteration sims + ingest nfl then nba"
  : > "$RUNDIR/worst-$nm.mb"; "$M" sample $nm "$RUNDIR/worst-$nm.mb" & local s1=$!
  local s2=""; if [ "$jdk" = 1 ]; then : > "$RUNDIR/worst-$nm.heap"; "$M" hsample $nm "$RUNDIR/worst-$nm.heap" & s2=$!; fi
  $LOAD --base http://localhost:$port --mode worst --rounds 1 --iterations 5000 --out "$RUNDIR/worst-$nm.csv" > "$RUNDIR/worst-$nm.txt" 2>&1 &
  local l=$!
  sleep 2
  for sp in nfl nba; do
    t0=$(date +%s)
    curl -sS -m 300 -o "$RUNDIR/ingest-$nm-$sp.json" -w "ingest $sp: HTTP %{http_code} in %{time_total}s\n" -X POST -H "X-Admin-Token: bk041-local-admin" "http://localhost:$port/api/ingest/players?sport=$sp" | tee -a "$RUNDIR/log.txt"
  done
  wait $l
  touch "$RUNDIR/worst-$nm.mb.stop" "$RUNDIR/worst-$nm.heap.stop"; sleep 4
  local oom; oom=$(docker inspect -f '{{.State.OOMKilled}} restarts={{.RestartCount}}' $nm)
  local extra=""; [ "$jdk" = 1 ] && extra="; $(heappeak "$RUNDIR/worst-$nm.heap")"
  NOGC=1 snap $nm "worst-end" "peak=$("$M" peak "$RUNDIR/worst-$nm.mb")MB$extra; OOMKilled=$oom; $(cat "$RUNDIR/worst-$nm.txt")"
}
worst $JRE 8098 0
worst $JDK 8099 1
log "rep $REP done"
docker rm -f $JRE $JDK >/dev/null
