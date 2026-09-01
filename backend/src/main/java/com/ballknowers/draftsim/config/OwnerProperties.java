package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-user "who am I" identity, used to auto-detect which draft slot belongs
 * to the account owner across ingested leagues (LeagueController.seats()'s
 * mySlot field). This project is explicitly single-user/single-tenant, so one
 * configured Sleeper user id is the whole requirement -- no settings screen, no
 * per-request identity.
 *
 * Blank/unset means auto-detection is off, the local-dev default -- same shape
 * as ApiSecurityProperties.token being blank meaning auth is off.
 */
@ConfigurationProperties(prefix = "draftsim.owner")
public record OwnerProperties(String sleeperUserId) {

    public boolean configured() {
        return sleeperUserId != null && !sleeperUserId.isBlank();
    }
}
