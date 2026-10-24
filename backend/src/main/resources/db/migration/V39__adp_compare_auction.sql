-- Spec 046: ADP compare (Sleeper dynasty/redraft + Yahoo) and auction drafts.
--
-- adp_snapshot gets two nullable columns rather than a new table, V2's reasoning: every reader
-- already keys on (sport, source, captured_on), and Yahoo's average price / share of leagues that
-- drafted the player are per-row provenance about the same fact. Null for every other source.
-- Three new source keys ride on it (sleeper_dynasty, sleeper_redraft, yahoo): about 1,900 rows a
-- day, which this table absorbs fine. Every board reader filters by source, so none sees them.
alter table adp_snapshot
    add column avg_cost        numeric(6,2),
    add column percent_drafted numeric(5,4);

-- Auction drafts. budget is settings.budget (null for a snake draft); amount is metadata.amount
-- on the pick (null for snake, or when Sleeper sent none). player_name / sleeper_player_id keep
-- what Sleeper called a sale whose player never resolved to a local row (spec 046 F4), so the
-- sales feed can still name it and a later player ingest can resolve it.
alter table draft
    add column budget int;
alter table draft_pick
    add column amount            int,
    add column player_name       text,
    add column sleeper_player_id text;

-- Yahoo players PlayerMatcher could not match to a local player (spec 046 F5). adp_snapshot is
-- keyed on player_id, so these have nowhere to live there. Replaced per
-- (sport, source, captured_on) in the same transaction as that capture's adp_snapshot rows.
create table adp_unmatched (
    id              bigserial primary key,
    sport           text not null,
    source          text not null,
    captured_on     date not null,
    name            text not null,
    team            text,
    positions       text,
    adp             numeric(6,2),
    avg_cost        numeric(6,2),
    percent_drafted numeric(5,4)
);
create index adp_unmatched_lookup_idx on adp_unmatched (sport, source, captured_on);
