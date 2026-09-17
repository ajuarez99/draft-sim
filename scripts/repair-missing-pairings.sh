#!/usr/bin/env bash
#
# Re-ingest every league chain whose pairings were lost to the score-cache gate.
#
# This CANNOT be done in SQL. league_matchup.matchup_id is the only pairing data
# in the schema and it was never written for the affected weeks -- there is no
# column to derive it from. The values exist only in Sleeper's matchups
# endpoint, so repair means asking Sleeper again, which is what the history
# ingest does.
#
# Requires the fix in 945371e to be DEPLOYED first. Against an old build the
# gate still skips the cached weeks and this script is a no-op that looks like
# it worked.
#
# Usage:
#   DATABASE_URL=... API=https://api.ballknowers.co ./scripts/repair-missing-pairings.sh
#   ./scripts/repair-missing-pairings.sh --apply        # actually run the ingests
#
# Without --apply it prints what it would do and changes nothing.

set -euo pipefail

# Git Bash (MSYS) rewrites an argument beginning "//" as a Windows path, which
# mangles a postgres:// URL into nonsense and makes psql silently drop every
# flag after it. Measured here: `psql "$URL" -t -A -c ...` warns "extra
# command-line argument -A ignored" and returns nothing. Excluding conversion
# and passing the URL through -d rather than positionally fixes it, and is
# harmless everywhere else.
export MSYS2_ARG_CONV_EXCL='*'

API="${API:-http://localhost:8080}"
DB="${DATABASE_URL:-postgres://draftsim:draftsim@localhost:5433/draftsim}"
APPLY="${1:-}"

heads_sql="
with affected as (
    select l.sleeper_id
    from league l
    join roster_week_points w on w.league_id = l.id
    left join league_matchup m on m.league_id = l.id and m.matchup_id is not null
    group by l.id, l.sleeper_id
    having count(distinct w.week) > count(distinct m.week)
),
heads as (
    select l.* from league l
    where not exists (select 1 from league c where c.previous_league_id = l.sleeper_id)
),
chain as (
    select h.sleeper_id as head_sleeper, member.sleeper_id as member
    from heads h
    join lateral (
        with recursive walk as (
            select h.sleeper_id, h.previous_league_id
            union all
            select l2.sleeper_id, l2.previous_league_id
            from league l2 join walk w on l2.sleeper_id = w.previous_league_id
        ) select sleeper_id from walk
    ) member on true
)
select distinct c.head_sleeper from chain c join affected a on a.sleeper_id = c.member;
"

echo "API: $API"
echo "Finding league chains with missing pairings..."

mapfile -t heads < <(psql -t -A -d "$DB" -c "$heads_sql" | sed '/^$/d')

if [ "${#heads[@]}" -eq 0 ]; then
    echo "Nothing to repair -- every scored week has its pairing."
    exit 0
fi

echo "${#heads[@]} chain(s) need re-ingesting:"
printf '  %s\n' "${heads[@]}"

if [ "$APPLY" != "--apply" ]; then
    echo
    echo "Dry run. Re-run with --apply to perform the ingests."
    exit 0
fi

for head in "${heads[@]}"; do
    echo
    echo "Re-ingesting $head ..."
    # Idempotent: every write on this path is an upsert, so re-running is safe.
    curl -fsS -X POST "$API/api/ingest/league-history/$head"
    echo
done

echo
echo "Re-checking..."
psql -d "$DB" -f "$(dirname "$0")/find-missing-pairings.sql"
