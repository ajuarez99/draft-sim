package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls Sleeper for a tracked draft's status and picks on a fixed interval, one
 * virtual thread per draft. Safe to call {@link #track} any time before a draft
 * starts -- it no-ops (status polling only, no pick ingest) while status is
 * pre_draft, starts ingesting once it observes drafting, and stops once it
 * observes complete.
 *
 * In-memory only: no persisted tracking state, no reconciliation across a process
 * restart. See claude/live-poller-plan.md decision 2 for why that's fine for a
 * single-instance, single-draft-night deployment.
 */
@Component
public class LiveDraftPoller {

    private static final Logger log = LoggerFactory.getLogger(LiveDraftPoller.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    private final SleeperClient sleeper;
    private final DraftRepository drafts;
    private final ManagerRepository managers;
    private final PlayerRepository players;

    private final ConcurrentHashMap<Long, Thread> active = new ConcurrentHashMap<>();

    public LiveDraftPoller(SleeperClient sleeper, DraftRepository drafts,
                           ManagerRepository managers, PlayerRepository players) {
        this.sleeper = sleeper;
        this.drafts = drafts;
        this.managers = managers;
        this.players = players;
    }

    public record TrackResult(boolean started, String status) {}

    /** Idempotent: calling twice for the same draft id starts exactly one poller. */
    public TrackResult track(DraftRepository.DraftRow draft) {
        AtomicBoolean started = new AtomicBoolean(false);
        active.computeIfAbsent(draft.id(), id -> {
            started.set(true);
            return spawn(draft);
        });
        return new TrackResult(started.get(), draft.status());
    }

    private Thread spawn(DraftRepository.DraftRow draft) {
        return Thread.ofVirtual().name("draft-poll-" + draft.id()).start(() -> loop(draft));
    }

    void loop(DraftRepository.DraftRow draft) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!pollOnce(draft)) break;
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.warn("poll tick failed for draft {}", draft.id(), e);
                // keep looping -- transient (Sleeper hiccup, network blip)
            }
        }
        active.remove(draft.id());
    }

    /** One Sleeper fetch + (conditionally) one upsert + one status write. No sleep -- unit-testable. */
    boolean pollOnce(DraftRepository.DraftRow draft) {
        Map<String, Object> raw = sleeper.draft(draft.sleeperDraftId());
        String status = raw.get("status") == null ? null : raw.get("status").toString();
        drafts.updateStatus(draft.id(), status);

        if ("complete".equals(status)) return false;
        if ("pre_draft".equals(status)) return true;

        List<Map<String, Object>> rawPicks = sleeper.draftPicks(draft.sleeperDraftId());
        Map<Integer, Long> slotLookup = new HashMap<>();
        draft.slotToManager().forEach((slotStr, managerIdObj) ->
                slotLookup.put(Integer.parseInt(slotStr), ((Number) managerIdObj).longValue()));

        Map<String, Long> managerByUserId = managers.idsBySleeperUserId();
        Map<String, Long> playerIdsBySleeperId = players.idsBySleeperId(Sport.NFL);

        List<DraftRepository.PickRow> rows = new ArrayList<>();
        if (rawPicks != null) {
            for (Map<String, Object> p : rawPicks) {
                rows.add(PickMapper.toPickRow(draft.id(), p, managerByUserId, slotLookup, playerIdsBySleeperId));
            }
        }
        drafts.upsertPicks(draft.id(), rows);
        return true;
    }

    /** So a bootRun restart doesn't leave dangling pollers still hitting Sleeper after shutdown began. */
    @PreDestroy
    public void shutdown() {
        active.values().forEach(Thread::interrupt);
    }
}
