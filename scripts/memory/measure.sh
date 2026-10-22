#!/usr/bin/env bash
# Spec 041 measurement helper (Git Bash). Shared-environment rules: containers are named bk041-*,
# host ports 8098/8099 only, database draftsim_041 inside draftsim-pg (:5433) only.
#
#   measure.sh start  <name> <image> <port> [extra JAVA_OPTS]   run a 2g/2cpu container
#   measure.sh snap   <name> <profile> [note]                    append one C3 row to $OUT
#   measure.sh sample <name> <file>                              background docker-stats sampler (1 line/s: epoch MB)
#   measure.sh hsample <name> <file>                             background jcmd heap sampler (epoch committedMB usedMB)
#   measure.sh peak   <file> [since-epoch]                       max MB column since an epoch
#   measure.sh warm   <port> [urls-file]                         the C2 GET sweep (X-Sleeper-User from the file)
#   measure.sh wait   <port>                                     wait for /api/health weightsLoaded
#
# `snap` reads: container MB (docker stats, always); and, only when the container has jcmd
# (the -jdk images): GC.heap_info committed+used BEFORE a GC, then GC.run and used AFTER it
# (NOGC=1 skips the GC.run so a recovery reading is not disturbed), and NMT total committed.
# C3 columns: profile | container MB | heap used after GC MB | heap committed MB | NMT committed MB | note
# Environment: OUT (table file), ADMIN_TOKEN is not used here.
set -u
OUT=${OUT:-$(dirname "$0")/../../specs/041-memory-footprint/measure-rows.md}
HERE=$(cd "$(dirname "$0")" && pwd)

to_mb() { # "345.2MiB" / "1.2GiB" / "800KiB" -> MB (1 decimal)
  awk -v s="$1" 'BEGIN{ n=s+0; if (s ~ /GiB/) n*=1024; else if (s ~ /KiB|kB/) n/=1024; else if (s ~ /MiB/) n=n; else if (s ~ /B/) n/=1048576; printf "%.1f", n }'
}
cmem() { to_mb "$(docker stats --no-stream --format '{{.MemUsage}}' "$1" | awk '{print $1}')"; }
has_jcmd() { docker exec "$1" sh -c 'command -v jcmd' >/dev/null 2>&1; }
# G1 (JDK 21.0.x prints "garbage-first heap   total 77824K, used 54623K [...]": total = committed;
# newer JDKs print "total reserved ..., committed ..., used ...")
heap_field() { # name field(committed|used) -> MB
  docker exec "$1" jcmd 1 GC.heap_info 2>/dev/null | awk -v f="$2" '/garbage-first heap/ {
    for (i=1;i<NF;i++) {
      if ($i=="committed") c=$(i+1)
      if ($i=="total" && $(i+1)!="reserved") t=$(i+1)
      if ($i=="used") u=$(i+1)
    }
    v = (f=="used") ? u : (c!="" ? c : t); gsub(/[^0-9]/,"",v); printf "%.1f", v/1024 }'
}
nmt_total() {
  docker exec "$1" jcmd 1 VM.native_memory summary 2>/dev/null | awk '/^Total:/ {
    for (i=1;i<=NF;i++) if ($i ~ /^committed=/) { v=$i; gsub(/[^0-9]/,"",v); printf "%.1f", v/1024 } ; exit }'
}

cmd=${1:-}; shift || true
case "$cmd" in
  start)
    name=$1; image=$2; port=$3; extra=${4:-}
    opts="${BASE_OPTS--XX:MaxRAMPercentage=75} $extra"   # analyze I1: on :main JAVA_OPTS REPLACES the image default, so BASE_OPTS defaults to it. On :041 images run with BASE_OPTS="" (flags are in the ENTRYPOINT)
    docker rm -f "$name" >/dev/null 2>&1
    docker run -d --name "$name" --memory=2g --memory-swap=2g --cpus=2 -p "$port:8080" ${DOCKER_ENV:-} \
      -e DB_URL=jdbc:postgresql://host.docker.internal:5433/draftsim_041 \
      -e ADMIN_TOKEN=bk041-local-admin -e JAVA_OPTS="$opts" "$image" ;;
  wait)
    port=$1
    for i in $(seq 1 90); do
      curl -fsS "http://localhost:$port/api/health" 2>/dev/null | grep -q '"weightsLoaded":true' && { echo ready after ${i}x2s; exit 0; }
      sleep 2
    done
    echo "NOT READY" >&2; exit 1 ;;
  snap)
    name=$1; profile=$2; note=${3:-}
    mb=$(cmem "$name"); hu=n/a; hc=n/a; nmt=n/a
    if has_jcmd "$name"; then
      hc=$(heap_field "$name" committed); nmt=$(nmt_total "$name")
      if [ "${NOGC:-0}" = 1 ]; then hu="$(heap_field "$name" used) (before GC)"
      else docker exec "$name" jcmd 1 GC.run >/dev/null 2>&1; hu=$(heap_field "$name" used); fi
    fi
    row="| $profile | $mb | $hu | $hc | $nmt | $note |"
    echo "$row"; echo "$row" >> "$OUT" ;;
  sample)
    name=$1; file=$2
    # streaming docker stats (~1-2 s cadence). Lines carry ANSI escapes, so extract the first "<n>MiB".
    # Stops when "$file.stop" exists (the next line read after that ends the loop and the pipe).
    rm -f "$file.stop"
    docker stats --format '{{.MemUsage}}' "$name" | while read -r line; do
      [ -e "$file.stop" ] && break
      v=$(echo "$line" | grep -o '[0-9.]*[KMG]iB' | head -1)
      [ -n "$v" ] && echo "$(date +%s) $(to_mb "$v")" >> "$file"
    done ;;
  hsample)
    name=$1; file=$2
    rm -f "$file.stop"
    while [ ! -e "$file.stop" ]; do echo "$(date +%s) $(heap_field "$name" committed) $(heap_field "$name" used)" >> "$file"; sleep 2; done ;;
  peak)
    file=$1; since=${2:-0}
    awk -v s="$since" '$1>=s && $2>m {m=$2} END{printf "%.1f\n", m}' "$file" ;;
  warm)
    port=$1; urls=${2:-$HERE/c2-urls.txt}
    n=0; bad=0
    while IFS=$'\t' read -r user path; do
      case "$user$path" in "#"*|"") continue ;; esac
      hdr=(); [ "$user" != "-" ] && hdr=(-H "X-Sleeper-User: $user")
      code=$(curl -s -o /dev/null -w '%{http_code}' "${hdr[@]}" "http://localhost:$port$path")
      n=$((n+1)); case "$code" in 2*) ;; *) bad=$((bad+1)); echo "non-2xx $code $path" >&2 ;; esac
    done < "$urls"
    echo "warm sweep: $n requests, $bad non-2xx" ;;
  *) sed -n '2,19p' "$0"; exit 2 ;;
esac
