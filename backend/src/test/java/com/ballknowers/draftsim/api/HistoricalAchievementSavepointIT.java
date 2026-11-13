package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalAchievementRepository;
import com.ballknowers.draftsim.historical.HistoricalAchievementService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** A REAL Postgres statement failure inside the per-game savepoint, on the real service path. */
@SpringBootTest
class HistoricalAchievementSavepointIT {
    private static final String U = "it-hsave-save";
    @Autowired JdbcTemplate jdbc;
    @Autowired HistoricalDataRepository data;
    @Autowired HistoricalGameService games;
    @Autowired HistoricalAchievementService achievements;
    @MockitoSpyBean HistoricalAchievementRepository repo;

    @BeforeEach @AfterEach
    void clean() {
        jdbc.update("delete from historical_achievement where sleeper_user_id like 'it-hsave-%'");
        jdbc.update("delete from historical_profile where sleeper_user_id like 'it-hsave-%'");
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-hsave-%'");
        HistoricalSyntheticSeason.clean(jdbc);
    }

    long finished(int slot) {
        long id = games.create(U, "NBA", slot, HistoricalSyntheticSeason.S).id();
        games.auto(id, HistoricalGameService.Scope.FINISH, U).orElseThrow();
        return id;
    }

    @Test
    void aFailedStatementInsideOneGameDoesNotAbortTheOthers() {
        HistoricalSyntheticSeason.seed(data);
        long bad = finished(2);
        long good = finished(6);
        long later = finished(9);
        AtomicLong failFor = new AtomicLong(bad);
        AtomicLong sqlFailures = new AtomicLong();
        doAnswer(inv -> {
            Object r = inv.callRealMethod();
            if (((Long) inv.getArgument(2)) == failFor.get()) {
                sqlFailures.incrementAndGet();
                jdbc.execute("select 1/0");   // a real server-side error: the transaction is now aborted
            }
            return r;
        }).when(repo).insertBadge(anyString(), anyString(), anyLong(), anyInt(), any(), any(), anyBoolean());

        int failures = achievements.settle(U);
        assertTrue(sqlFailures.get() >= 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_game_award where game_id = ?", Integer.class, bad));
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_achievement where game_id = ?", Integer.class, bad));
        assertEquals(1, jdbc.queryForObject("select count(*) from historical_game_award where game_id = ?", Integer.class, good));
        assertEquals(1, jdbc.queryForObject("select count(*) from historical_game_award where game_id = ?", Integer.class, later));

        failFor.set(-1);
        assertEquals(0, achievements.settle(U));
        assertEquals(3, jdbc.queryForObject("select count(*) from historical_game_award where sleeper_user_id = ?", Integer.class, U));
        assertEquals(1, jdbc.queryForObject("select count(*) from historical_achievement where sleeper_user_id = ? and code = 'FIRST_GAME'", Integer.class, U));
    }
}
