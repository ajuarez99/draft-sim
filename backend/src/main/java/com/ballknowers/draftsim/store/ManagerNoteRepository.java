package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A person's private note about a manager, keyed (author, manager, sport) -- V25.
 *
 * Every read takes the author, and there is deliberately no method that reads a
 * manager's notes across authors: a note being visible only to its writer is
 * enforced by there being no query that could return anyone else's.
 */
@Repository
public class ManagerNoteRepository {

    private final JdbcClient db;

    public ManagerNoteRepository(JdbcClient db) {
        this.db = db;
    }

    /** This author's notes for one sport, by manager id. */
    public Map<Long, String> notesBy(String authorSleeperUserId, Sport sport) {
        Map<Long, String> out = new HashMap<>();
        db.sql("select manager_id, note from manager_note where author_sleeper_user_id = ? and sport = ?")
                .params(authorSleeperUserId, sport.code())
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Optional<String> noteBy(String authorSleeperUserId, long managerId, Sport sport) {
        return db.sql("select note from manager_note where author_sleeper_user_id = ? and manager_id = ? and sport = ?")
                .params(authorSleeperUserId, managerId, sport.code())
                .query(String.class)
                .optional();
    }

    public void save(String authorSleeperUserId, long managerId, Sport sport, String note) {
        db.sql("""
                insert into manager_note (author_sleeper_user_id, manager_id, sport, note, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (author_sleeper_user_id, manager_id, sport) do update set
                    note = excluded.note,
                    updated_at = now()
                """)
                .params(authorSleeperUserId, managerId, sport.code(), note)
                .update();
    }

    /** Deletes only this author's note; anyone else's is untouched. */
    public void delete(String authorSleeperUserId, long managerId, Sport sport) {
        db.sql("delete from manager_note where author_sleeper_user_id = ? and manager_id = ? and sport = ?")
                .params(authorSleeperUserId, managerId, sport.code())
                .update();
    }
}
