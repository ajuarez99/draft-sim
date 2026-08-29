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

    public void saveManual(long managerId, Sport sport, ManualTendencies manual) {
        db.sql("""
                insert into manager_profile (manager_id, sport, manual_json, updated_at)
                values (?, ?, ?::jsonb, now())
                on conflict (manager_id, sport) do update set
                    manual_json = excluded.manual_json,
                    updated_at = now()
                """)
                .params(managerId, sport.code(), JsonUtil.write(manual))
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
