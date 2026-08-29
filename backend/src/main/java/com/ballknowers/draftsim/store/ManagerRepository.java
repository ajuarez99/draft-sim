package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class ManagerRepository {

    private final JdbcClient db;

    public ManagerRepository(JdbcClient db) {
        this.db = db;
    }

    /** Keyed on Sleeper user id. Display name is refreshed but never identifying. */
    public long upsert(String sleeperUserId, String displayName) {
        return db.sql("""
                insert into manager (sleeper_user_id, display_name)
                values (?, ?)
                on conflict (sleeper_user_id) do update set display_name = excluded.display_name
                returning id
                """)
                .params(sleeperUserId, displayName)
                .query(Long.class)
                .single();
    }

    public Optional<String> displayName(long managerId) {
        return db.sql("select display_name from manager where id = ?")
                .param(managerId).query(String.class).optional();
    }

    public Map<Long, String> names() {
        return db.sql("select id, display_name from manager")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2) == null ? "?" : rs.getString(2)))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
