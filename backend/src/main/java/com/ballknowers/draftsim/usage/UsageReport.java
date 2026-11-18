package com.ballknowers.draftsim.usage;

import java.util.List;
import java.util.Map;

/**
 * The owner's usage read (spec 076, contracts/usage-api.md). Records, not {@code Map.of}: {@code leagueName} is
 * nullable (a league row deleted since). {@code web/src/api.ts} mirrors this field for field. Days are
 * {@code YYYY-MM-DD} strings in America/New_York; sports are lowercase {@code nba} / {@code nfl}.
 */
public record UsageReport(
        String timezone,
        String from,
        String to,
        /** Per site, the earliest day with a view, league-view or event row; a site with none has no entry. */
        List<SiteSince> countingSince,
        /** ET date of the oldest Redraft game row; null = no games. */
        String gamesSince,
        /** Whether draftsim.owner.sleeper-user-id is set. */
        boolean ownerConfigured,
        List<ViewDay> viewsByDay,
        List<ViewRoute> viewsByRoute,
        List<LeagueView> leagueViews,
        List<EventRow> events,
        long rejected,
        List<FunnelDay> funnel) {

    public record SiteSince(String site, String since) {}

    public record ViewDay(String day, String site, long views) {}

    public record ViewRoute(String site, String route, long views) {}

    public record LeagueView(String leagueId, String leagueName, String sport, long views) {}

    public record EventRow(String site, String name, Map<String, String> props, long count) {}

    public record FunnelDay(String day, String sport, long started, long finished, long abandoned, long drafting,
                            long guestStarted, long guestFinished, long unknownStarted,
                            long dailyStarted, long dailyFinished,
                            /** The owner's SIGNED-IN games (started_as_guest false); 0 when no owner is configured. */
                            long ownerStarted, long ownerFinished, long ownerAbandoned, long ownerDrafting,
                            long ownerDailyStarted, long ownerDailyFinished) {}
}
