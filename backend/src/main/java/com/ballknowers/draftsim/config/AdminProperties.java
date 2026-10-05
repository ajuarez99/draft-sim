package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator's admin secret ({@code draftsim.admin.token}, from
 * {@code ADMIN_TOKEN}), presented as the {@code X-Admin-Token} header.
 *
 * <p>Server-side only: never a {@code VITE_*} variable, so it can never be baked
 * into a browser bundle. <b>Blank means admin is disabled, and every admin-only
 * route then refuses</b> (fail closed) -- the opposite of {@link ApiSecurityProperties},
 * where a blank token turns the gate off. That difference is deliberate: a
 * missing secret must never open a door.
 *
 * <p>What it unlocks: every {@code /api/ingest/**} route, and an override for the
 * league/draft/mock membership checks so an operator can still curl a manual
 * pick on draft night ({@link AdminAccess}). Commissioner-only writes needed it
 * from 2026-09-29 to 2026-10-05 (claude/audit-2026-09-28/04, amended); they no
 * longer do, because no commissioner but the operator could ever hold it.
 */
@ConfigurationProperties(prefix = "draftsim.admin")
public record AdminProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }

    public boolean matches(String presented) {
        return SecretCompare.matches(token, presented);
    }
}
