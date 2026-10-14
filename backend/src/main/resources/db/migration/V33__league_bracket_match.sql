-- specs/034-rivalry-schedule-grid T001 (data-model; plan.md F1/F2/F5): Sleeper's WINNERS bracket,
-- one row per bracket match, per league-season.
--
-- Why it exists: a playoff WEEK is not a playoff GAME. Sleeper also pairs the consolation teams in
-- those weeks (measured: half of the 14 scored pairings in a 6-team league's playoff weeks), and
-- nothing stored tells the two apart. SleeperClient.winnersBracket() had no caller until this.
--
-- Numbered V33, not V32: V32 belongs to the 033-trade-verdicts branch (plan.md F1).
--
-- Nullable slots are real. While an earlier round is unplayed, Sleeper sends t1/t2 absent and a
-- t1_from/t2_from pointer instead; a pre-playoff season holds a provisional bracket seeded from
-- current standings (plan.md F2), so a row here is not proof the pairing is final. The reader
-- (RivalryGameKind) therefore matches on the week's round and refuses a round with a null slot.
create table league_bracket_match (
    league_id        bigint not null references league (id) on delete cascade,
    match_id         int    not null,      -- Sleeper "m"
    round            int    not null,      -- Sleeper "r"
    t1_roster_id     int,
    t2_roster_id     int,
    winner_roster_id int,                  -- Sleeper "w"; null until played
    loser_roster_id  int,                  -- Sleeper "l"
    -- Sleeper "p": 1 = final, 3 = third place, 5 = fifth place; null = an elimination game.
    placement        int,
    primary key (league_id, match_id)
);
