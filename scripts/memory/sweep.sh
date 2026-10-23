#!/usr/bin/env bash
# Spec 041 T009: one sweep trial on a fresh bk041:main-jdk container (the OLD settings), flags changed at runtime with jcmd VM.set_flag.
# Usage: sweep.sh <name> <port> <periodic|ratio> <value>      periodic: G1PeriodicGCInterval=<ms>; ratio: <max>/<min> e.g. 30/10
# Steps: fresh container, rest, stress (5 rounds x 12 members x 500 it), set the flag, read container MB + committed heap at +1 and +6 min
# (ratio trials then force GC.run once to show where the ratio would shrink the heap to), then a second stress for resim p50/p95.
# Appends one line to $SWEEP_OUT. Container MB is read on the JDK image (about 45 MB above the JRE one at rest), so compare within the sweep only.
set -u
NAME=$1; PORT=$2; KIND=$3; VAL=$4
HERE=$(cd "$(dirname "$0")" && pwd); M=$HERE/measure.sh
OUT=${SWEEP_OUT:?}; IMAGE=${IMAGE:-bk041:main-jdk}
MEMBERS=$(docker exec draftsim-pg psql -U draftsim -d draftsim_041 -Atc "select string_agg(m.sleeper_user_id,',' order by lm.id) from league_member lm join manager m on m.id=lm.manager_id where lm.league_id=210")
LOAD="python -I $HERE/load.py --base http://localhost:$PORT --draft 1339351318128517120 --members $MEMBERS --startstate-file $HERE/startstate-nba.json --mode stress --rounds 5 --iterations 500 --out /dev/null"
hc() { docker exec $NAME jcmd 1 GC.heap_info | awk '/garbage-first heap/{for(i=1;i<NF;i++){if($i=="total"&&$(i+1)!="reserved")t=$(i+1);if($i=="used")u=$(i+1)}gsub(/[^0-9]/,"",t);gsub(/[^0-9]/,"",u);printf "%.0f/%.0f",t/1024,u/1024}'; }
mb() { docker stats --no-stream --format '{{.MemUsage}}' $NAME | awk '{v=$1; n=v+0; if(v~/GiB/)n*=1024; printf "%.0f",n}'; }
"$M" start $NAME $IMAGE $PORT "-XX:NativeMemoryTracking=summary" >/dev/null; "$M" wait $PORT >/dev/null || exit 1
sleep 45
REST="$(mb) MB, heap committed/used $(hc)"
S1=$($LOAD | head -1)
END="$(mb) MB, heap $(hc)"
case $KIND in
  periodic) docker exec $NAME jcmd 1 VM.set_flag G1PeriodicGCInterval $VAL >/dev/null ;;
  both) docker exec $NAME jcmd 1 VM.set_flag G1PeriodicGCInterval ${VAL%%,*} >/dev/null; R=${VAL#*,}; docker exec $NAME jcmd 1 VM.set_flag MaxHeapFreeRatio ${R%/*} >/dev/null; docker exec $NAME jcmd 1 VM.set_flag MinHeapFreeRatio ${R#*/} >/dev/null ;;  # value like 60000,30/10
  ratio) docker exec $NAME jcmd 1 VM.set_flag MaxHeapFreeRatio ${VAL%/*} >/dev/null; docker exec $NAME jcmd 1 VM.set_flag MinHeapFreeRatio ${VAL#*/} >/dev/null ;;
esac
sleep 60;  R1="$(mb) MB, heap $(hc)"
sleep 300; R6="$(mb) MB, heap $(hc)"
G=""
if [ $KIND = ratio ]; then docker exec $NAME jcmd 1 GC.run >/dev/null; sleep 3; G="afterGC.run $(mb) MB, heap $(hc)"; fi
S2=$($LOAD | head -1)
echo "| $KIND=$VAL | rest $REST | stress-end $END | +1min $R1 | +6min $R6 | $G | stress1: ${S1#calls=} | stress2 (next round): ${S2#calls=} |" | tee -a "$OUT"
docker rm -f $NAME >/dev/null
