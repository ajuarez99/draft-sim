package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.engine.WeeklyReportService.Result;
import com.ballknowers.draftsim.domain.Sport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Turns a captured {@code GET /weekly-report/{week}} response (the output of
 * {@code WeeklyReportController.body}) back into the {@link Result} it was built from. Fields a
 * sport does not carry are absent in the JSON and null here, as in production.
 */
final class RecapFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecapFixtures() {}

    static Result nfl() { return load("/recap/nfl-2026-week3.json"); }

    static Result nba() { return load("/recap/nba-2025-week10.json"); }

    static Result load(String resource) {
        try (InputStream in = RecapFixtures.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing fixture " + resource);
            return fromJson(JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Result fromJson(JsonNode n) {
        return new Result(n.get("available").asBoolean(), text(n, "reason"), n.get("season").asInt(),
                n.hasNonNull("requestedSeason") ? n.get("requestedSeason").asInt() : null,
                n.get("week").asInt(), Sport.fromCode(n.get("sport").asText()),
                n.get("playersPlayMultiplePerPeriod").asBoolean(),
                list(n, "matchups", m -> new WeeklyReportService.Matchup(side(m.get("home")), side(m.get("away")))),
                list(n, "topPerformers", p -> new WeeklyReportService.Performer(text(p, "playerId"),
                        text(p, "playerName"), text(p, "position"), text(p, "teamName"), p.get("points").asDouble(),
                        text(p, "team"), text(p, "opponent"), bool(p, "isAway"), text(p, "avatarId"))),
                list(n, "bestNights", b -> new WeeklyReportService.NightPerformance(text(b, "playerId"),
                        text(b, "playerName"), text(b, "position"), text(b, "teamName"), b.get("points").asDouble(),
                        LocalDate.parse(b.get("date").asText()), text(b, "opponent"), bool(b, "isAway"))),
                list(n, "bestWeek", w -> new WeeklyReportService.PlayerWeek(text(w, "playerId"),
                        text(w, "playerName"), text(w, "position"), text(w, "teamName"),
                        w.get("totalPoints").asDouble(), w.get("gamesPlayed").asInt())),
                text(n, "basis"),
                list(n, "sectionsUnavailable", u -> new WeeklyReportService.SectionUnavailable(
                        text(u, "section"), text(u, "reason"))),
                list(n, "awards", a -> new WeeklyReportService.Award(text(a, "kind"), text(a, "teamName"),
                        text(a, "detail"))),
                list(n, "awardsOmitted", o -> new WeeklyReportService.OmittedAward(text(o, "kind"), text(o, "reason"))),
                n.get("latestScoredWeek").asInt(), n.get("latestFinalWeek").asInt(), n.get("weekFinal").asBoolean());
    }

    private static WeeklyReportService.Side side(JsonNode s) {
        return new WeeklyReportService.Side(s.get("rosterId").asInt(), text(s, "teamName"), text(s, "username"),
                text(s, "avatarId"), text(s, "record"), s.get("points").asDouble(), s.get("isMe").asBoolean());
    }

    private static <T> List<T> list(JsonNode n, String field, Function<JsonNode, T> f) {
        if (!n.has(field)) return null;
        List<T> out = new ArrayList<>();
        for (JsonNode e : n.get(field)) out.add(f.apply(e));
        return out;
    }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }

    private static Boolean bool(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asBoolean() : null; }
}
