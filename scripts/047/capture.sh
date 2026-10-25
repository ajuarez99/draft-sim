#!/usr/bin/env bash
# Spec 047 T002/T033: capture JSON for the before/after diff. Usage: capture.sh <outdir> [port]
# Same X-Sleeper-User on every call (popsharky, manager 7). Run only against the scratch DB backend.
set -u
OUT="$1"; PORT="${2:-8087}"; USER_ID=1122386008709910528
mkdir -p "$OUT"
get() { curl -s -H "X-Sleeper-User: $USER_ID" -H "X-Admin-Token: ${ADMIN_TOKEN:-scratch047}" "http://localhost:$PORT$1" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.stringify(JSON.parse(s),null,1))}catch(e){console.log(s)}})' > "$OUT/$2.json"; }
# FBK 2026, NBA 2026, West Coast 2026
for pair in "fbk:1346366555759341568" "nba:1339351318115946496" "wc:1389361939561332736"; do
  n="${pair%%:*}"; id="${pair##*:}"
  get "/api/leagues/$id/rivalries" "rivalries-$n"
  get "/api/leagues/$id/history" "history-$n"
done
for m in 7 23 55; do get "/api/managers/$m/history" "manager-$m-history"; done
ls -la "$OUT"
