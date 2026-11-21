package com.ballknowers.draftsim.historical;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The share preview of a finished Redraft game (spec 079): its words and its PNG. Both come from
 * {@link HistoricalGameService#publicGame}, so a game that is not COMPLETE or does not exist is empty (404), and a
 * today's-daily game is already reduced; {@link SharePreview#from} then reads only what the reduced view keeps.
 *
 * <p>A COMPLETE game never changes, so a rendered PNG is cached by id in a bounded LRU (256 entries, roughly 30-60 KB
 * each). Only a successful render is cached. Renders are CPU and allocation work on enumerable ids, so at most
 * {@link #RENDER_SLOTS} run at once and one that cannot get a slot in {@link #WAIT_SECONDS} seconds fails with
 * {@link SharePreviewBusyException} (503); the og:image URL stays in the page and the unfurler retries.
 */
@Service
public class SharePreviewService {

    static final int CACHE_ENTRIES = 256;
    static final int RENDER_SLOTS = 2;
    static final long WAIT_SECONDS = 2;

    private final HistoricalGameService games;
    private final Semaphore slots = new Semaphore(RENDER_SLOTS);
    private final Object lock = new Object();
    private final Map<Long, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    public SharePreviewService(HistoricalGameService games) {
        this.games = games;
    }

    /** The game of the day's number (spec 078) comes from the public state itself: null for free play. */
    public Optional<SharePreview> preview(long id) {
        return games.publicGame(id).map(g -> SharePreview.from(g, g.state().dailyNumber()));
    }

    /**
     * @throws SharePreviewBusyException no render slot within the wait
     * @throws SharePreviewRenderException the render itself failed (headless or font problem)
     */
    public Optional<byte[]> png(long id) {
        synchronized (lock) {
            byte[] hit = cache.get(id);
            if (hit != null) return Optional.of(hit);
        }
        Optional<SharePreview> p = preview(id);
        if (p.isEmpty()) return Optional.empty();
        boolean got;
        try {
            got = slots.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SharePreviewBusyException();
        }
        if (!got) throw new SharePreviewBusyException();
        byte[] bytes;
        try {
            bytes = SharePreviewImage.render(p.get().card());
        } finally {
            slots.release();
        }
        synchronized (lock) {
            cache.put(id, bytes);
        }
        return Optional.of(bytes);
    }
}
