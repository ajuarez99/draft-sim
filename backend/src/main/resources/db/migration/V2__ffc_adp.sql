-- FFC as a real market ADP source alongside sleeper_search_rank and the
-- observed-draft-order blend. See claude/adp-sources.md.
--
-- New columns on adp_snapshot rather than a new table: every reader already
-- keys on (sport, source, captured_on), and these are per-row provenance
-- about the same fact -- this player, this source, this capture -- not a
-- different kind of row. All nullable/defaulted because sleeper_search_rank
-- and blend rows never populate them.
alter table adp_snapshot
    add column stdev           numeric(6,2),
    add column source_teams    int,
    add column source_scoring  text,
    add column sample_drafts   int,
    add column derived         boolean not null default false,
    add column derivation      text;

-- Hand-filled as unmatched names turn up in the ingest miss log. Not yet
-- populated or read by application code this session -- see
-- claude/adp-sources.md #7 and HANDOFF's note on what this migration covers.
create table player_alias (
    id           bigserial primary key,
    source       text   not null,
    source_name  text   not null,
    player_id    bigint not null references player (id) on delete cascade,
    unique (source, source_name)
);
