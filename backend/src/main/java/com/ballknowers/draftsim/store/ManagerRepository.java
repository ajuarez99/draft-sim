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

    /** Keyed on Sleeper user id. Display name and avatar are refreshed but never identifying. */
    public long upsert(String sleeperUserId, String displayName, String avatarId) {
        return db.sql("""
                insert into manager (sleeper_user_id, display_name, avatar_id)
                values (?, ?, ?)
                on conflict (sleeper_user_id) do update set
                    display_name = excluded.display_name,
                    avatar_id = excluded.avatar_id
                returning id
                """)
                .params(sleeperUserId, displayName, avatarId)
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

    /** Sleeper avatar id per manager, same wire form SleeperUserController resolves at sign-in. Absent (not empty string) where Sleeper has none. */
    public Map<Long, String> avatarIds() {
        Map<Long, String> out = new java.util.HashMap<>();
        db.sql("select id, avatar_id from manager where avatar_id is not null")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Keyed on Sleeper user id, same shape as {@link PlayerRepository#idsBySleeperId}. */
    public Map<String, Long> idsBySleeperUserId() {
        Map<String, Long> out = new java.util.HashMap<>();
        db.sql("select sleeper_user_id, id from manager")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
