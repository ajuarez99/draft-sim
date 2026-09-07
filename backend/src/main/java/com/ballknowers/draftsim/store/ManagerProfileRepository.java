package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ManualTendencies;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * feature_json and manual_json are written by different code paths on purpose:
 * ingest owns the fitted half, the API owns the stated half, and neither upsert
 * touches the other's column. Re-running ingest can never wipe what the user typed.
 */
@Repository
public class ManagerProfileRepository {

    private final JdbcClient db;

    public ManagerProfileRepository(JdbcClient db) {
        this.db = db;
    }

    public void saveFitted(long managerId, Sport sport, String featureJson, int draftsObserved) {
        db.sql("""
                insert into manager_profile (manager_id, sport, feature_json, drafts_observed, updated_at)
                values (?, ?, ?::jsonb, ?, now())
                on conflict (manager_id, sport) do update set
                    feature_json = excluded.feature_json,
                    drafts_observed = excluded.drafts_observed,
                    updated_at = now()
                """)
                .params(managerId, sport.code(), featureJson, draftsObserved)
                .update();
    }

    /**
     * Merges the stated tendencies into manual_json rather than replacing the column.
     *
     * The three keys this record owns are always written, explicit nulls included
     * (Jackson serializes them, and {@link #parse} reads a null as "no opinion"), so
     * a PUT still fully replaces reachBias/unpredictability/note exactly as it did
     * when this was a whole-column overwrite. What changes is that any OTHER key in
     * manual_json survives the write.
     *
     * That matters because Java only ever round-trips three fields: anything else
     * stored alongside them -- a note's structured reading, per
     * claude/player-affinity.md -- would otherwise be destroyed by the next save
     * from either tendencies UI, silently, with no error and no way to notice. Same
     * failure shape as the adp_at_time null-wipe in HANDOFF.md, and the reason
     * `||` is a merge here instead of an assignment.
     *
     * To wipe everything including those other keys, use {@link #clearManual}.
     */
    public void saveManual(long managerId, Sport sport, ManualTendencies manual) {
        db.sql("""
                insert into manager_profile (manager_id, sport, manual_json, updated_at)
                values (?, ?, ?::jsonb, now())
                on conflict (manager_id, sport) do update set
                    manual_json = manager_profile.manual_json || excluded.manual_json,
                    updated_at = now()
                """)
                .params(managerId, sport.code(), JsonUtil.write(manual))
                .update();
    }

    /**
     * Hard-resets manual_json to {}, dropping every key including ones this class
     * does not know about.
     *
     * DELETE /tendencies means "forget what I said about this seat", and a reading
     * derived from a note that no longer exists is garbage, not state worth
     * preserving -- so this is deliberately a replace where {@link #saveManual} is
     * a merge.
     */
    public void clearManual(long managerId, Sport sport) {
        db.sql("""
                insert into manager_profile (manager_id, sport, manual_json, updated_at)
                values (?, ?, '{}'::jsonb, now())
                on conflict (manager_id, sport) do update set
                    manual_json = '{}'::jsonb,
                    updated_at = now()
                """)
                .params(managerId, sport.code())
                .update();
    }

    public Map<Long, ManualTendencies> manualBySport(Sport sport) {
        Map<Long, ManualTendencies> out = new HashMap<>();
        db.sql("select manager_id, manual_json::text from manager_profile where sport = ?")
                .param(sport.code())
                .query((rs, i) -> Map.entry(rs.getLong(1), parse(rs.getString(2))))
                .list()
                .forEach(e -> {
                    if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue());
                });
        return out;
    }

    public ManualTendencies manualFor(long managerId, Sport sport) {
        return db.sql("select manual_json::text from manager_profile where manager_id = ? and sport = ?")
                .params(managerId, sport.code())
                .query(String.class)
                .optional()
                .map(ManagerProfileRepository::parse)
                .orElse(ManualTendencies.EMPTY);
    }

    private static ManualTendencies parse(String json) {
        Map<String, Object> m = JsonUtil.readMap(json);
        if (m.isEmpty()) return ManualTendencies.EMPTY;
        return new ManualTendencies(
                m.get("reachBias") instanceof Number n ? n.doubleValue() : null,
                m.get("unpredictability") instanceof Number n ? n.doubleValue() : null,
                m.get("note") == null ? null : m.get("note").toString());
    }
}
