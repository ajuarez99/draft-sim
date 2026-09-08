package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.AdpProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * multi-sport-and-rebrand.md Phase 5: fantasyfootballcalculator.com is a
 * football-only vendor with no basketball data, and IngestController's
 * requireNflForNow guard -- which used to be the only thing stopping a
 * basketball sport from reaching this class -- is gone. So this class must
 * now refuse basketball itself, before it ever fetches or matches anything,
 * rather than trusting a caller to keep gating it.
 */
@ExtendWith(MockitoExtension.class)
class FfcAdpServiceSportGuardTest {

    @Mock private FfcClient client;
    @Mock private AdpProperties cfg;
    @Mock private PlayerRepository players;
    @Mock private BoardRepository boards;

    @Test
    void ingestForBasketballSkipsWithoutTouchingTheFfcClientOrThePlayerOrBoardStores() {
        FfcAdpService service = new FfcAdpService(client, cfg, players, boards);

        FfcAdpService.Result result = service.ingest(Sport.NBA);

        assertFalse(result.enabled(), "basketball must not report FFC as a live source");
        assertEquals(0, result.rows());
        assertEquals(0, result.matched());
        assertEquals(0, result.unmatched());
        assertTrue(result.derivation().toLowerCase().contains("football-only")
                        || result.derivation().toLowerCase().contains("skipped"),
                "derivation should explain why nba got nothing: " + result.derivation());

        verifyNoInteractions(client, players, boards);
    }
}
