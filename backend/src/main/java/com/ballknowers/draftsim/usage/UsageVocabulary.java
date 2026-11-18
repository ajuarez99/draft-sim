package com.ballknowers.draftsim.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The closed set of things a browser may tell us it did (spec 076). Loaded from {@code usage-vocabulary.json},
 * which is generated from {@code web/src/analytics.ts}; a vitest test keeps the two equal.
 *
 * <p>Nothing the client typed is kept except a value that matched this vocabulary: routes and event names are
 * returned as the vocabulary's own strings, and props are REBUILT from validated values (keys sorted,
 * {@code k=v} joined by {@code ;}). A league id is the one free-form field; the caller checks it against the
 * league table before it is stored.
 *
 * <p>Fails soft: a missing or malformed resource logs ONE error and the vocabulary is empty, so every send is
 * ignored. The application still starts; a broken vocabulary must never stop the API.
 */
@Component
public class UsageVocabulary {

    private static final Logger log = LoggerFactory.getLogger(UsageVocabulary.class);
    static final String RESOURCE = "/usage-vocabulary.json";

    /** Longest league id we will even look up. Sleeper ids are 18 digits; this is a bound, not a rule. */
    static final int MAX_LEAGUE_ID = 64;

    public enum Status {
        /** Valid; the key is usable. */
        OK,
        /** A known site sent something outside the vocabulary: counted (in memory) as rejected for that site. */
        REJECTED,
        /** Unknown site, unparsable, or a vocabulary that failed to load: counted nowhere. */
        IGNORED
    }

    /**
     * The outcome of validating one body. For {@code OK}: {@code kind} is view / league-view / event and the
     * strings are the canonical values. {@code leagueId} is non-empty only for league-view and is still
     * UNVERIFIED (the caller looks it up).
     */
    public record Result(Status status, String site, String kind, String route, String name, String props,
                         String leagueId) {
        static final Result IGNORED = new Result(Status.IGNORED, "", "", "", "", "", "");

        static Result rejected(String site) {
            return new Result(Status.REJECTED, site, "", "", "", "", "");
        }
    }

    private final Map<String, Set<String>> routesBySite;
    /** event name -> prop key -> allowed values. */
    private final Map<String, Map<String, Set<String>>> events;

    @Autowired
    public UsageVocabulary() {
        this(openResource());
    }

    private static InputStream openResource() {
        InputStream in = UsageVocabulary.class.getResourceAsStream(RESOURCE);
        if (in == null) log.error("usage vocabulary resource {} is missing; every usage send will be ignored", RESOURCE);
        return in;
    }

    /** For tests: the stream is closed here. A null stream is the missing-resource case. */
    UsageVocabulary(InputStream in) {
        Map<String, Set<String>> sites = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> evs = new LinkedHashMap<>();
        if (in != null) {
            try (in) {
                JsonNode root = new ObjectMapper().readTree(in);
                parse(root, sites, evs);
            } catch (IOException | RuntimeException e) {
                sites.clear();
                evs.clear();
                log.error("usage vocabulary resource {} is unreadable ({}); every usage send will be ignored",
                        RESOURCE, e.getClass().getSimpleName());
            }
        }
        this.routesBySite = Collections.unmodifiableMap(sites);
        this.events = Collections.unmodifiableMap(evs);
    }

    private static void parse(JsonNode root, Map<String, Set<String>> sites, Map<String, Map<String, Set<String>>> evs) {
        if (root == null || !root.isObject() || !root.path("sites").isObject() || !root.path("events").isObject()) {
            throw new IllegalArgumentException("shape");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = root.get("sites").fields(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().isArray()) throw new IllegalArgumentException("routes");
            Set<String> routes = new HashSet<>();
            for (JsonNode r : e.getValue()) routes.add(requireText(r));
            sites.put(e.getKey(), routes);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = root.get("events").fields(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().isObject()) throw new IllegalArgumentException("props");
            Map<String, Set<String>> props = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> pi = e.getValue().fields(); pi.hasNext(); ) {
                var p = pi.next();
                if (!p.getValue().isArray()) throw new IllegalArgumentException("values");
                Set<String> vals = new HashSet<>();
                for (JsonNode v : p.getValue()) vals.add(requireText(v));
                props.put(p.getKey(), vals);
            }
            evs.put(e.getKey(), props);
        }
    }

    private static String requireText(JsonNode n) {
        if (n == null || !n.isTextual()) throw new IllegalArgumentException("text");
        return n.asText();
    }

    /** True when the vocabulary loaded and has at least one site. */
    public boolean loaded() {
        return !routesBySite.isEmpty();
    }

    public boolean knowsSite(String site) {
        return site != null && routesBySite.containsKey(site);
    }

    public Set<String> sites() {
        return routesBySite.keySet();
    }

    public Set<String> routes(String site) {
        return routesBySite.getOrDefault(site, Set.of());
    }

    public Map<String, Map<String, Set<String>>> events() {
        return events;
    }

    /**
     * Validates a parsed body. The caller has already enforced the size cap and parsed JSON; a non-object body
     * is {@code IGNORED} here too. The {@code kind} field is optional: when absent it is inferred from the other
     * fields; when present it must agree with them.
     */
    public Result validate(JsonNode body) {
        if (body == null || !body.isObject()) return Result.IGNORED;
        JsonNode siteNode = body.get("site");
        if (siteNode == null || !siteNode.isTextual() || !knowsSite(siteNode.asText())) return Result.IGNORED;
        String site = siteNode.asText();

        String kind = inferKind(body);
        JsonNode kindNode = body.get("kind");
        if (kindNode != null && (!kindNode.isTextual() || !kindNode.asText().equals(kind))) return Result.rejected(site);

        Set<String> keys = new HashSet<>();
        body.fieldNames().forEachRemaining(keys::add);
        keys.remove("kind");

        switch (kind) {
            case "view" -> {
                if (!keys.equals(Set.of("site", "route"))) return Result.rejected(site);
                String route = canonicalRoute(site, body.get("route"));
                if (route == null) return Result.rejected(site);
                return new Result(Status.OK, site, "view", route, "", "", "");
            }
            case "league-view" -> {
                if (!keys.equals(Set.of("site", "route", "league"))) return Result.rejected(site);
                String route = canonicalRoute(site, body.get("route"));
                JsonNode lg = body.get("league");
                if (route == null || lg == null || !lg.isTextual()) return Result.rejected(site);
                String id = lg.asText();
                if (id.isEmpty() || id.length() > MAX_LEAGUE_ID || !id.chars().allMatch(UsageVocabulary::idChar)) {
                    return Result.rejected(site);
                }
                return new Result(Status.OK, site, "league-view", route, "", "", id);
            }
            case "event" -> {
                if (!Set.of("site", "name", "props").containsAll(keys)) return Result.rejected(site);
                JsonNode nm = body.get("name");
                if (!nm.isTextual()) return Result.rejected(site);
                String given = nm.asText();
                String name = null;
                for (String known : events.keySet()) if (known.equals(given)) name = known;
                if (name == null) return Result.rejected(site);
                String props = canonicalProps(events.get(name), body.get("props"));
                if (props == null) return Result.rejected(site);
                return new Result(Status.OK, site, "event", "", name, props, "");
            }
            default -> {
                return Result.rejected(site);
            }
        }
    }

    private static boolean idChar(int c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }

    private static String inferKind(JsonNode body) {
        if (body.has("name")) return "event";
        if (body.has("league")) return "league-view";
        return "view";
    }

    /** Returns the vocabulary's own string for the route, or null when it is not in the site's set. */
    private String canonicalRoute(String site, JsonNode route) {
        if (route == null || !route.isTextual()) return null;
        String r = route.asText();
        for (String known : routes(site)) if (known.equals(r)) return known;
        return null;
    }

    /** Canonical {@code k=v;k=v}, built from the vocabulary's strings; null when the props are not exactly the declared set. */
    private static String canonicalProps(Map<String, Set<String>> allowed, JsonNode props) {
        if (props == null || props.isNull()) props = new ObjectMapper().createObjectNode();
        if (!props.isObject() || props.size() != allowed.size()) return null;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> a : new TreeMap<>(allowed).entrySet()) {
            JsonNode v = props.get(a.getKey());
            if (v == null || !v.isTextual()) return null;
            String given = v.asText();
            String matched = null;
            for (String ok : a.getValue()) if (ok.equals(given)) matched = ok;
            if (matched == null) return null;
            parts.add(a.getKey() + "=" + matched);
        }
        return String.join(";", parts);
    }
}
