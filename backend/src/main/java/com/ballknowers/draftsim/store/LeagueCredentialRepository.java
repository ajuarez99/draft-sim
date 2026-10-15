package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.ingest.espn.EspnCredential;
import com.ballknowers.draftsim.security.CredentialCipher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Stored ESPN cookies, one set per ESPN league (spec 035 research R13, plan F2/F14/F20).
 *
 * <p>There is deliberately no method here that returns cookies to a controller. {@link #forIngest}
 * is the only query that selects {@code ciphertext}, and it returns an {@link EspnCredential}
 * (redacted {@code toString}, no public getters) for the ingest service to hand to
 * {@code EspnClient}. Existence and ownership are separate cheap reads.
 */
@Repository
public class LeagueCredentialRepository {

    public static final String ESPN = "ESPN";

    private final JdbcClient db;
    private final CredentialCipher cipher;

    public LeagueCredentialRepository(JdbcClient db, CredentialCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    /**
     * Stores (or replaces) the sealed cookies. A replace is accepted only from the existing owner,
     * so a second user adding the same league cannot take the credential over.
     *
     * @return true when the credential is now the one supplied; false when another manager owns the
     *         stored one and it was left untouched
     */
    public boolean put(String sport, String platformLeagueId, long ownerManagerId, EspnCredential credential) {
        CredentialCipher.Sealed sealed = credential.seal(cipher);   // throws CREDENTIALS_NOT_CONFIGURED if no key
        int rows = db.sql("""
                insert into league_credential (platform, sport, platform_league_id, owner_manager_id,
                                               ciphertext, nonce, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (platform, sport, platform_league_id) do update set
                    ciphertext = excluded.ciphertext,
                    nonce = excluded.nonce,
                    key_version = 1,
                    updated_at = excluded.updated_at
                where league_credential.owner_manager_id = excluded.owner_manager_id
                """)
                .params(ESPN, sport, platformLeagueId, ownerManagerId, sealed.ciphertext(), sealed.nonce(),
                        Timestamp.from(Instant.now()))
                .update();
        return rows > 0;
    }

    /** The only read of {@code ciphertext}. Empty when nothing is stored. */
    public Optional<EspnCredential> forIngest(String sport, String platformLeagueId) {
        return db.sql("""
                select ciphertext, nonce from league_credential
                where platform = ? and sport = ? and platform_league_id = ?
                """)
                .params(ESPN, sport, platformLeagueId)
                .query((rs, i) -> new CredentialCipher.Sealed(rs.getBytes(2), rs.getBytes(1)))
                .optional()
                .map(sealed -> EspnCredential.open(cipher, sealed));
    }

    public boolean exists(String sport, String platformLeagueId) {
        return Boolean.TRUE.equals(db.sql("""
                select exists (select 1 from league_credential
                               where platform = ? and sport = ? and platform_league_id = ?)
                """)
                .params(ESPN, sport, platformLeagueId)
                .query(Boolean.class)
                .single());
    }

    public Optional<Long> owner(String sport, String platformLeagueId) {
        return db.sql("""
                select owner_manager_id from league_credential
                where platform = ? and sport = ? and platform_league_id = ?
                """)
                .params(ESPN, sport, platformLeagueId)
                .query(Long.class)
                .optional();
    }

    public void delete(String sport, String platformLeagueId) {
        db.sql("delete from league_credential where platform = ? and sport = ? and platform_league_id = ?")
                .params(ESPN, sport, platformLeagueId)
                .update();
    }

    /** Stamped after an ESPN read that used the stored cookies succeeded. */
    public void markWorked(String sport, String platformLeagueId, Instant at) {
        db.sql("""
                update league_credential set last_worked_at = ?
                where platform = ? and sport = ? and platform_league_id = ?
                """)
                .params(Timestamp.from(at), ESPN, sport, platformLeagueId)
                .update();
    }
}
