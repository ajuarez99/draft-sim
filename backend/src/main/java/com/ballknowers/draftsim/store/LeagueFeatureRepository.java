package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-league premium entitlements ({@code league_feature}, V29; specs/020-ai-weekly-recap). */
@Repository
public class LeagueFeatureRepository {

    /** The only feature that exists so far; the table's check constraint lists it too. */
    public static final String RECAP = "RECAP";

    private final JdbcClient db;

    public LeagueFeatureRepository(JdbcClient db) {
        this.db = db;
    }

    public boolean has(long leagueId, String feature) {
        return Boolean.TRUE.equals(db.sql(
                        "select exists (select 1 from league_feature where league_id = ? and feature = ?)")
                .params(leagueId, feature)
                .query(Boolean.class)
                .single());
    }

    /** Idempotent: granting twice keeps the first grant's timestamp and note. */
    public void grant(long leagueId, String feature, String note) {
        db.sql("""
                insert into league_feature (league_id, feature, note) values (?, ?, ?)
                on conflict (league_id, feature) do nothing
                """)
                .params(leagueId, feature, note)
                .update();
    }

    public void revoke(long leagueId, String feature) {
        db.sql("delete from league_feature where league_id = ? and feature = ?")
                .params(leagueId, feature)
                .update();
    }
}
