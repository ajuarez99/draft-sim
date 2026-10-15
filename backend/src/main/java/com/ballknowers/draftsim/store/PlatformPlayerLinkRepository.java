package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which app player an external (ESPN) player id resolved to, or why it did not (spec 035, F11/F17).
 * A hand fix is {@code outcome = 'MANUAL'} here, not a {@code player_alias} row.
 */
@Repository
public class PlatformPlayerLinkRepository {

    public static final String MATCHED = "MATCHED";
    public static final String MANUAL = "MANUAL";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String UNMATCHED = "UNMATCHED";
    public static final String ESPN_ONLY_POSITION = "ESPN_ONLY_POSITION";

    private final JdbcClient db;

    public PlatformPlayerLinkRepository(JdbcClient db) {
        this.db = db;
    }

    /** A player the matcher refused, as the owner is shown the list (spec 035 FR-007): ESPN's own label and why. */
    public record UnmatchedPlayer(String name, String position, String team, String outcome) {}

    public record Link(String platform, String sport, String externalId, Long playerId, String outcome,
                       String externalName, String externalPosition, String externalTeam) {}

    public Map<String, Link> find(String platform, String sport, Collection<String> externalIds) {
        Map<String, Link> out = new HashMap<>();
        if (externalIds == null || externalIds.isEmpty()) return out;
        String in = String.join(", ", Collections.nCopies(externalIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(platform);
        params.add(sport);
        params.addAll(externalIds);
        for (Link l : db.sql("""
                select platform, sport, external_id, player_id, outcome, external_name, external_position, external_team
                from platform_player_link where platform = ? and sport = ? and external_id in (%s)
                """.formatted(in))
                .params(params)
                .query((rs, i) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)))
                .list()) {
            out.put(l.externalId(), l);
        }
        return out;
    }

    /**
     * Never downgrades a MATCHED or MANUAL row to another outcome (F17): an UNMATCHED or AMBIGUOUS
     * row can become MATCHED later (the player table grows), but a resolved one is reused forever.
     * A MATCHED row may be refreshed with a new name/team, and a MANUAL row is only ever replaced
     * by another MANUAL.
     */
    public void upsert(Link row, Instant at) {
        db.sql("""
                insert into platform_player_link (platform, sport, external_id, player_id, outcome,
                                                  external_name, external_position, external_team, resolved_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (platform, sport, external_id) do update set
                    player_id = case
                        when platform_player_link.outcome = 'MANUAL' and excluded.outcome <> 'MANUAL'
                            then platform_player_link.player_id
                        when platform_player_link.outcome = 'MATCHED' and excluded.outcome not in ('MATCHED', 'MANUAL')
                            then platform_player_link.player_id
                        else excluded.player_id end,
                    outcome = case
                        when platform_player_link.outcome = 'MANUAL' and excluded.outcome <> 'MANUAL'
                            then platform_player_link.outcome
                        when platform_player_link.outcome = 'MATCHED' and excluded.outcome not in ('MATCHED', 'MANUAL')
                            then platform_player_link.outcome
                        else excluded.outcome end,
                    external_name = excluded.external_name,
                    external_position = excluded.external_position,
                    external_team = excluded.external_team,
                    resolved_at = excluded.resolved_at
                """)
                .params(row.platform(), row.sport(), row.externalId(), row.playerId(), row.outcome(),
                        row.externalName(), row.externalPosition(), row.externalTeam(), Timestamp.from(at))
                .update();
    }

    /** {@code externalId -> ESPN's own name}, for rendering a player the matcher refused (F10). */
    public Map<String, String> externalNames(String platform, String sport, Collection<String> externalIds) {
        Map<String, String> out = new HashMap<>();
        for (Link l : find(platform, sport, externalIds).values()) out.put(l.externalId(), l.externalName());
        return out;
    }
}
