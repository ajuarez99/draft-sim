-- specs/036-projection-foundation data-model E1/E2. Frozen pre-game projections, so a later
-- accuracy number grades what was actually predicted before each game and not a line revised
-- after it (research R1: Sleeper's past rows all carry a last_modified after their own game).
--
-- V36 and not a lower number (research R11, amended after plan review): 033 holds V34 and 035's
-- tree holds V35. Flyway runs with outOfOrder=false, so this file must sort after anything
-- already applied anywhere it will run. Append-only: never edit this file once applied; any
-- change is the next V<n+1>.
--
-- A separate table from V15's player_projection on purpose. That one is a MUTABLE CACHE: keyed by
-- week, rewritten whenever it is older than six hours, and refreshing it is its whole job. A
-- snapshot is the opposite contract -- a record that stops changing at a cutoff -- and folding the
-- two together would put a "never overwrite after X" rule on a table whose job is to overwrite.
--
-- Points are never stored. The line is stored raw and scored at read time under the asking
-- league's own scoring, because the table is shared by every league in the sport.
--
-- The freeze is structural, not a convention of the writer (plan amendment B2):
--   * check (fetched_at < cutoff_at) -- a row cannot be INSERTED if it was fetched after its own
--     cutoff. The CHECK runs before ON CONFLICT is considered, so the service drops late rows
--     before the statement; a late row in a batch aborts the whole batch.
--   * a BEFORE UPDATE trigger that returns NULL (skip the row, no error) once now() has passed
--     the row's cutoff_at, and pins cutoff_at so it can never be moved to un-freeze a row.
-- "Frozen" is therefore not a stored flag that could go stale; it is now() >= cutoff_at.
create table projection_snapshot (
    id                 bigserial primary key,
    sport              text        not null,           -- Sport.code(): nba / nfl
    season             int         not null,
    week               int         not null,           -- the projection row's own week
    sleeper_player_id  text        not null,
    game_id            text        not null,           -- joins player_game.game_id / sport_schedule.game_id
    game_date          date        not null,           -- Sleeper's local/Eastern game date (measured, R3/N1)
    opponent           text,
    team               text,
    stats              jsonb       not null,           -- the line as served, minus adp_* / pos_adp_*
    season_avg_stats   jsonb,                          -- NBA only: player_season_projection.stats at fetch time (R6)
    source             text,
    source_modified_at timestamptz,
    fetched_at         timestamptz not null,
    cutoff_at          timestamptz not null,           -- fixed at first insert
    unique (sport, season, sleeper_player_id, game_id),
    check (fetched_at < cutoff_at)
);

-- The accuracy read is always "this sport and season, every snapshot" at once.
create index projection_snapshot_season_idx on projection_snapshot (sport, season);

create function projection_snapshot_freeze() returns trigger as $$
begin
    if now() >= OLD.cutoff_at then
        return null;            -- frozen: skip this row's update silently, abort nothing
    end if;
    NEW.cutoff_at := OLD.cutoff_at;
    return NEW;
end;
$$ language plpgsql;

create trigger projection_snapshot_freeze_trg
    before update on projection_snapshot
    for each row execute function projection_snapshot_freeze();

-- E2: football's weekly cache starts keeping the stat line it already receives and used to drop
-- (US3). Nullable: rows written before V36 have none, and the exact-scoring path falls back to
-- the column pick row by row.
alter table player_projection add column stats jsonb;
