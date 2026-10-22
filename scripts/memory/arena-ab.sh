#!/usr/bin/env bash
# T012: A/B MALLOC_ARENA_MAX=2 on the JRE image (bk041:041): rest, stress, recovery at +5 min (and +1, +10 not needed). Arm A on 8098, arm B on 8099, same load at once.
# Usage: arena-ab.sh <rep> ; appends to $ARENA_OUT. Image from $IMAGE (default bk041:041).
set -u
HERE=$(cd "$(dirname "$0")" && pwd); M=$HERE/measure.sh; REP=$1; IMAGE=${IMAGE:-bk041:041}
MEMBERS=$(docker exec draftsim-pg psql -U draftsim -d draftsim_041 -Atc "select string_agg(m.sleeper_user_id,',' order by lm.id) from league_member lm join manager m on m.id=lm.manager_id where lm.league_id=210")
LOAD="python -I $HERE/load.py --draft 1339351318128517120 --members $MEMBERS --startstate-file $HERE/startstate-nba.json --mode stress --rounds 5 --iterations 500 --out /dev/null"
mb() { docker stats --no-stream --format '{{.MemUsage}}' $1 | awk '{v=$1; n=v+0; if(v~/GiB/)n*=1024; printf "%.0f",n}'; }
export BASE_OPTS=""
DOCKER_ENV="" "$M" start bk041-arA $IMAGE 8098 "" >/dev/null
DOCKER_ENV="-e MALLOC_ARENA_MAX=2" "$M" start bk041-arB $IMAGE 8099 "" >/dev/null
"$M" wait 8098 >/dev/null; "$M" wait 8099 >/dev/null; sleep 60
RA=$(mb bk041-arA); RB=$(mb bk041-arB)
$LOAD --base http://localhost:8098 > /tmp/arA.txt & $LOAD --base http://localhost:8099 > /tmp/arB.txt & wait
EA=$(mb bk041-arA); EB=$(mb bk041-arB)
sleep 300
VA=$(mb bk041-arA); VB=$(mb bk041-arB)
echo "| rep$REP | A (default) rest $RA, stress-end $EA, +5min $VA | B (MALLOC_ARENA_MAX=2) rest $RB, stress-end $EB, +5min $VB | A $(head -1 /tmp/arA.txt | cut -d' ' -f1-3,5-6) | B $(head -1 /tmp/arB.txt | cut -d' ' -f1-3,5-6) |" | tee -a "$ARENA_OUT"
docker rm -f bk041-arA bk041-arB >/dev/null
