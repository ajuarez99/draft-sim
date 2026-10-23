#!/usr/bin/env bash
# Per-holder measurements for spec 041 baseline.md (H2, H3, H5, H10) on a fresh JDK container.
# Usage: holders.sh [image=bk041:main-jdk]   (container bk041-holders, port 8099). Prints raw numbers.
set -u
HERE=$(cd "$(dirname "$0")" && pwd); M=$HERE/measure.sh
IMG=${1:-bk041:main-jdk}; C=bk041-holders; P=8099
DRAFT=1339351318128517120; L=1339351318115946496; U=1122386008709910528
MEMBERS=$(docker exec draftsim-pg psql -U draftsim -d draftsim_041 -Atc "select string_agg(m.sleeper_user_id,',' order by lm.id) from league_member lm join manager m on m.id=lm.manager_id where lm.league_id=210")
M1=${MEMBERS%%,*}
LOAD="python -I $HERE/load.py --draft $DRAFT --members $M1 --startstate-file $HERE/startstate-nba.json --base http://localhost:$P --mode worst --rounds 1 --seed 41"
used_after_gc() { docker exec $C sh -c 'jcmd 1 GC.run >/dev/null; jcmd 1 GC.heap_info' | awk '/garbage-first heap/{for(i=1;i<NF;i++) if($i=="used"){v=$(i+1);gsub(/[^0-9]/,"",v);printf "%.1f",v/1024}}'; }
"$M" start $C "$IMG" $P "-XX:NativeMemoryTracking=summary" >/dev/null; "$M" wait $P || exit 1
sleep 45
echo "H-rest: used after GC = $(used_after_gc) MB; container $(OUT=/dev/null "$M" snap $C holders-rest | cut -d'|' -f3) MB"
docker exec $C jcmd 1 VM.native_memory summary > /tmp/bk041-nmt-rest.txt

echo "== H3: NBA stats page (loads NBA 2025 fallback season, 29143 rows; 2026 has none)"
b=$(used_after_gc)
curl -s -o /dev/null -H "X-Sleeper-User: $U" "http://localhost:$P/api/leagues/$L/stats?window=SEASON"
a=$(used_after_gc); echo "H3 nba: before=$b after=$a MB delta=$(awk -v a=$a -v b=$b 'BEGIN{printf "%.1f",a-b}') MB -> bytes/row=$(awk -v a=$a -v b=$b 'BEGIN{printf "%.0f",(a-b)*1048576/29143}')"
b2=$a
curl -s -o /dev/null -H "X-Sleeper-User: $U" "http://localhost:$P/api/leagues/1346366555759341568/stats?window=SEASON"
a2=$(used_after_gc); echo "H3 nfl: before=$b2 after=$a2 delta=$(awk -v a=$a2 -v b=$b2 'BEGIN{printf "%.1f",a-b}') MB (NFL 2025 26490 + NFL 2026 6229 rows if both loaded)"

echo "== warm sweep then NMT"
"$M" warm $P >/dev/null; used_base=$(used_after_gc); echo "H-warm-all: used after GC = $used_base MB"
docker exec $C jcmd 1 VM.native_memory summary > /tmp/bk041-nmt-warm.txt

echo "== H2: 5000-iteration sim, GC.run sampled mid-run (x3)"
echo "base (after a 500 sim and GC) used = $($LOAD --iterations 500 --out /dev/null >/dev/null; used_after_gc) MB"
for r in 1 2 3; do
  base=$(used_after_gc)
  $LOAD --iterations 5000 --out /tmp/bk041-h2.csv > /tmp/bk041-h2.txt 2>&1 & lp=$!
  sleep 1.5
  s=""; for k in 1 2 3; do
    if kill -0 $lp 2>/dev/null; then s="$s $(used_after_gc)"; else s="$s (run already finished)"; fi
  done
  wait $lp; echo "H2 rep$r: base=$base MB, mid-run used after GC =$s | $(cat /tmp/bk041-h2.txt)"
done

echo "== H5: ingest. used (no GC, tight loop) and live (GC.run loop)"
for sp in nfl nba nfl nba nfl nba; do
  base=$(used_after_gc)
  docker exec $C sh -c 'for i in $(seq 1 30); do jcmd 1 GC.heap_info | grep "garbage-first"; done' > /tmp/bk041-h5-raw.txt &
  hp=$!
  curl -s -o /dev/null -m 120 -X POST -H "X-Admin-Token: bk041-local-admin" "http://localhost:$P/api/ingest/players?sport=$sp"
  wait $hp
  rawmax=$(grep -o "used [0-9]*K" /tmp/bk041-h5-raw.txt | awk '{gsub(/K/,"",$2); if($2>m)m=$2} END{printf "%.1f", m/1024}')
  echo "H5 $sp (plain sampling): base=$base MB, max used seen=$rawmax MB (includes garbage), samples=$(wc -l < /tmp/bk041-h5-raw.txt)"
done
for sp in nfl nba nfl nba; do
  base=$(used_after_gc)
  docker exec $C sh -c 'for i in $(seq 1 12); do jcmd 1 GC.run >/dev/null; jcmd 1 GC.heap_info | grep "garbage-first"; done' > /tmp/bk041-h5-live.txt &
  hp=$!
  t0=$(date +%s.%N)
  curl -s -o /dev/null -m 120 -X POST -H "X-Admin-Token: bk041-local-admin" "http://localhost:$P/api/ingest/players?sport=$sp"
  t1=$(date +%s.%N); wait $hp
  mx=$(grep -o "used [0-9]*K" /tmp/bk041-h5-live.txt | awk '{gsub(/K/,"",$2); if($2>m)m=$2} END{printf "%.1f", m/1024}')
  echo "H5 $sp (GC.run loop): base=$base MB, max live seen=$mx MB, ingest took $(awk -v a=$t0 -v b=$t1 'BEGIN{printf "%.1f",b-a}') s"
done
echo "== H10: NMT summary at rest / after warm are in /tmp/bk041-nmt-rest.txt and -warm.txt"
for f in rest warm; do echo "-- $f"; grep -E "^-  *(Java Heap|Class|Thread|Code|GC|Compiler|Internal|Other|Symbol|Native Memory Tracking|Shared class|Metaspace|Arena|Total)|^Total" /tmp/bk041-nmt-$f.txt | sed 's/ (.*//' ; done
docker rm -f $C >/dev/null
