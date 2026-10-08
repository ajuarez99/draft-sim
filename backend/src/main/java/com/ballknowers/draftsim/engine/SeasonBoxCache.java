package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One entry per (sport, season) of the season's raw {@code player_game} rows, kept in a compact form
 * (specs/022-player-stat-analysis research R6 as amended after review F7, F8, N2).
 *
 * <p><b>What is stored</b>: the raw {@link SeasonGame} (with {@code isAway}) and {@link TeamGame} rows,
 * not only the cleaned lines, because Trends' {@code oneGameShare} reads every non-team row including
 * the All-Star game. Each game's numeric stats are interned key indexes plus {@code double} values
 * behind a read-only {@link Map} view. The {@link NbaGameLines} result over those rows is computed
 * once per load and stored with them.
 *
 * <p><b>Validity</b>: {@code seasonToken} is read <b>before</b> the rows, so a write landing between
 * the two reads leaves the stored token behind the data and the next {@code get} reloads. The token is
 * checked on every {@code get}. While {@link #markRefreshing} says an ingest of that season is in
 * flight, a changed token is ignored (every refresh re-stamps {@code fetched_at}, so honouring it would
 * reload continuously) and the existing entry is served; with no entry yet, it loads.
 * {@link #invalidate} drops validity explicitly.
 *
 * <p><b>Concurrency</b>: lock-free single flight (a {@link ConcurrentHashMap} of
 * {@link CompletableFuture}s, the pattern of {@code refresh/SingleFlight}, as this class's own
 * instance). Never {@code synchronized}: on Java 21 a virtual thread blocked on a monitor pins its
 * carrier. The loading thread runs the load itself; concurrent callers join its future.
 *
 * <p><b>Immutability</b>: every list, map and set in an entry is unmodifiable and shared between
 * requests, so a caller that sorts takes a copy.
 */
@Service
public class SeasonBoxCache {

    /** What the cache loads from. The repository in production; a fake in tests. */
    interface Source {
        SeasonToken token(Sport sport, int season);

        List<SeasonGame> games(Sport sport, int season);

        List<TeamGame> teamGames(Sport sport, int season);
    }

    /**
     * One season, loaded. {@code games} and {@code teamGames} are the raw rows; {@code lines} the
     * {@link NbaGameLines} over them.
     */
    public record Season(SeasonToken token, List<SeasonGame> games, List<TeamGame> teamGames, NbaGameLines lines) {}

    private record Key(Sport sport, int season) {}

    private record Stored(Season season, long generation) {}

    private final Source source;
    private final ConcurrentHashMap<Key, Stored> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, AtomicLong> generations = new ConcurrentHashMap<>();
    /** Overlapping refreshes of one season each hold a count; "refreshing" is a count above 0. */
    private final ConcurrentHashMap<Key, AtomicInteger> refreshing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Stored>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public SeasonBoxCache(PlayerGameRepository repo) {
        this(new Source() {
            @Override public SeasonToken token(Sport sport, int season) {
                return repo.seasonToken(sport, season);
            }
            @Override public List<SeasonGame> games(Sport sport, int season) {
                return repo.seasonPlayerGames(sport, season);
            }
            @Override public List<TeamGame> teamGames(Sport sport, int season) {
                return repo.seasonTeamGames(sport, season);
            }
        });
    }

    SeasonBoxCache(Source source) {
        this.source = source;
    }

    /**
     * The season's {@code (count, max fetched_at)} token, read from the same {@link Source} the cache
     * validates against (spec 022 N17), so "does this season have stored games" is one definition.
     * Does not load or cache anything.
     */
    public SeasonToken token(Sport sport, int season) {
        return source.token(sport, season);
    }

    public Season get(Sport sport, int season) {
        Key key = new Key(sport, season);
        long gen = generation(key).get();
        Stored have = entries.get(key);
        if (have != null && have.generation() == gen) {
            if (isRefreshing(key)) return have.season();     // a changed token is the refresh's own doing
            if (Objects.equals(have.season().token(), source.token(sport, season))) return have.season();
        }
        return reload(key);
    }

    /** The next {@code get} of this season reloads, whatever the token says. */
    public void invalidate(Sport sport, int season) {
        generation(new Key(sport, season)).incrementAndGet();
    }

    /**
     * While any mark is outstanding, a changed token does not trigger a reload of an existing entry.
     * Marks are counted: each {@code true} must be paired with one {@code false}, so two overlapping
     * refreshes of a season keep it "refreshing" until both end.
     */
    public void markRefreshing(Sport sport, int season, boolean refreshing) {
        Key key = new Key(sport, season);
        if (refreshing) {
            this.refreshing.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        } else {
            AtomicInteger count = this.refreshing.get(key);
            if (count != null) count.updateAndGet(n -> Math.max(0, n - 1));     // never below 0
        }
    }

    private boolean isRefreshing(Key key) {
        AtomicInteger count = refreshing.get(key);
        return count != null && count.get() > 0;
    }

    private AtomicLong generation(Key key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong());
    }

    private Season reload(Key key) {
        return loadOrJoin(key, true).season();
    }

    /**
     * Loads the season, or joins the load already running. A joined load that started before an
     * {@link #invalidate} carries an older generation and would hand back pre-invalidate rows, so a
     * join that finds its result stale starts a fresh load, once ({@code mayRetry}).
     */
    private Stored loadOrJoin(Key key, boolean mayRetry) {
        CompletableFuture<Stored> mine = new CompletableFuture<>();
        CompletableFuture<Stored> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            Stored joined = join(running);
            if (mayRetry && joined.generation() < generation(key).get()) return loadOrJoin(key, false);
            return joined;
        }
        try {
            long gen = generation(key).get();       // before the load: an invalidate during it leaves this stale
            Stored loaded = new Stored(load(key), gen);
            entries.put(key, loaded);
            // Release the key BEFORE completing, so a continuation calling get() starts a new load
            // instead of joining this finished one (SingleFlight does the same).
            inFlight.remove(key, mine);
            mine.complete(loaded);
            return loaded;
        } catch (Throwable t) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(t);
            throw t;
        }
    }

    private static Stored join(CompletableFuture<Stored> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    private Season load(Key key) {
        SeasonToken token = source.token(key.sport(), key.season());     // before the rows (F8)
        List<SeasonGame> rawGames = source.games(key.sport(), key.season());
        List<TeamGame> rawTeams = source.teamGames(key.sport(), key.season());
        Dictionary dict = new Dictionary();
        List<SeasonGame> games = new ArrayList<>(rawGames.size());
        for (SeasonGame g : rawGames) {
            games.add(new SeasonGame(g.sleeperPlayerId(), g.gameId(), g.gameDate(), g.opponent(),
                    dict.compact(g.stats()), g.week(), g.isAway()));
        }
        List<TeamGame> teams = new ArrayList<>(rawTeams.size());
        for (TeamGame t : rawTeams) {
            teams.add(new TeamGame(t.code(), t.gameId(), t.date(), t.opponent(), dict.compact(t.stats())));
        }
        List<SeasonGame> frozenGames = Collections.unmodifiableList(games);
        List<TeamGame> frozenTeams = Collections.unmodifiableList(teams);
        return new Season(token, frozenGames, frozenTeams, NbaGameLines.of(frozenGames, frozenTeams));
    }

    // ------------------------------------------------------------------ compact stats

    /** The season's interned stat keys. Mutated only during a load, then read-only. */
    private static final class Dictionary {
        private final Map<String, Integer> index = new HashMap<>();
        private final List<String> names = new ArrayList<>();

        Map<String, Object> compact(Map<String, Object> stats) {
            if (stats == null || stats.isEmpty()) return Collections.emptyMap();
            int n = 0;
            for (Object v : stats.values()) if (v instanceof Number) n++;
            if (n == 0) return Collections.emptyMap();
            long[] packed = new long[n];            // (key index << 32) | position, sorted by key index
            double[] raw = new double[n];
            int i = 0;
            for (Map.Entry<String, Object> e : stats.entrySet()) {
                if (!(e.getValue() instanceof Number num)) continue;
                Integer found = index.get(e.getKey());
                if (found == null) {
                    found = names.size();
                    names.add(e.getKey().intern());
                    index.put(e.getKey(), found);
                }
                raw[i] = num.doubleValue();
                packed[i] = ((long) found << 32) | i;
                i++;
            }
            Arrays.sort(packed);
            int[] keys = new int[n];
            double[] values = new double[n];
            for (int j = 0; j < n; j++) {
                keys[j] = (int) (packed[j] >> 32);
                values[j] = raw[(int) (packed[j] & 0xFFFFFFFFL)];
            }
            return new CompactStats(this, keys, values);
        }

        String name(int k) {
            return names.get(k);
        }

        int indexOf(Object key) {
            Integer k = key instanceof String s ? index.get(s) : null;
            return k == null ? -1 : k;
        }
    }

    /**
     * A read-only {@code Map<String,Object>} over interned key indexes and {@code double} values.
     * {@code get} of an absent key is null (never 0); values are {@link Double}. Non-numeric source
     * values are not kept: every reader of these maps treats a non-number as absent.
     */
    static final class CompactStats extends AbstractMap<String, Object> {
        private final Dictionary dict;
        private final int[] keys;
        private final double[] values;

        CompactStats(Dictionary dict, int[] keys, double[] values) {
            this.dict = dict;
            this.keys = keys;
            this.values = values;
        }

        @Override
        public Object get(Object key) {
            int k = dict.indexOf(key);
            if (k < 0) return null;
            int at = Arrays.binarySearch(keys, k);
            return at < 0 ? null : (Object) values[at];
        }

        @Override
        public boolean containsKey(Object key) {
            int k = dict.indexOf(key);
            return k >= 0 && Arrays.binarySearch(keys, k) >= 0;
        }

        @Override
        public int size() {
            return keys.length;
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() {
                    return keys.length;
                }
                @Override public Iterator<Map.Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        int i = 0;
                        @Override public boolean hasNext() {
                            return i < keys.length;
                        }
                        @Override public Map.Entry<String, Object> next() {
                            if (i >= keys.length) throw new NoSuchElementException();
                            Map.Entry<String, Object> e =
                                    new AbstractMap.SimpleImmutableEntry<>(dict.name(keys[i]), (Object) values[i]);
                            i++;
                            return e;
                        }
                    };
                }
            };
        }
    }
}
