package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.ingest.SportSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The stored season schedule (specs/017-nba-schedule-grid, data-model.md,
 * {@code V27__sport_schedule.sql}). Shared by every league of the sport.
 *
 * <p>A write replaces the whole (sport, season) set in one transaction, so a failure part-way
 * leaves the previous rows. An empty list is a no-op, never a wipe (FR-002). Dates bind as
 * {@link LocalDate} and the timestamp as {@link OffsetDateTime}, never {@code java.sql.Timestamp}
 * (AGENTS.md bug class 3), as in {@link SportTrendingRepository}.
 */
@Repository
public class SportScheduleRepository {

    private static final Logger log = LoggerFactory.getLogger(SportScheduleRepository.class);

    private final JdbcTemplate jdbc;

    public SportScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Via the Spring proxy only (a self-call would skip the transaction). {@code games} should come
     * from {@link SportSchedule#games()}; a repeated id still cannot throw, it updates.
     */
    @Transactional
    public void replaceSeason(String sport, int season, List<SportSchedule.Game> games, OffsetDateTime fetchedAt) {
        if (games.isEmpty()) {
            log.warn("sport_schedule: {} {} -- empty schedule, keeping the stored rows", sport, season);
            return;
        }
        jdbc.update("delete from sport_schedule where sport = ? and season = ?", sport, season);
        jdbc.batchUpdate("""
                insert into sport_schedule (sport, season, game_id, week, game_date, home, away, status, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (sport, season, game_id) do update set
                    week = excluded.week,
                    game_date = excluded.game_date,
                    home = excluded.home,
                    away = excluded.away,
                    status = excluded.status,
                    fetched_at = excluded.fetched_at
                """,
                games, 500,
                (ps, g) -> {
                    ps.setString(1, sport);
                    ps.setInt(2, season);
                    ps.setString(3, g.gameId());
                    ps.setInt(4, g.week());
                    if (g.date() == null) ps.setNull(5, java.sql.Types.DATE);
                    else ps.setObject(5, g.date());
                    ps.setString(6, g.home());
                    ps.setString(7, g.away());
                    ps.setString(8, g.status());
                    ps.setObject(9, fetchedAt);
                });
    }

    /** Ordered by week, date, then game id. */
    public List<SportSchedule.Game> forSeason(String sport, int season) {
        return jdbc.query("""
                select game_id, week, game_date, status, home, away
                from sport_schedule where sport = ? and season = ?
                order by week, game_date, game_id
                """,
                (rs, n) -> new SportSchedule.Game(
                        rs.getString("game_id"),
                        rs.getInt("week"),
                        rs.getObject("game_date", LocalDate.class),
                        rs.getString("status"),
                        rs.getString("home"),
                        rs.getString("away")),
                sport, season);
    }

    /** The write time of the stored schedule; empty when none is stored. */
    public Optional<OffsetDateTime> fetchedAt(String sport, int season) {
        return jdbc.query("select max(fetched_at) from sport_schedule where sport = ? and season = ?",
                        (rs, n) -> rs.getObject(1, OffsetDateTime.class), sport, season)
                .stream().filter(Objects::nonNull).findFirst();
    }
}
