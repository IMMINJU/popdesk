package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * OIDC sign-in (S11-AC-30). Counted in memory since the app started; a restart empties it. Failures on the
 * account's side (no account, suspended, wrong app) are not counted. Never the issuer, the client ID, or the
 * secret.
 */
public record OidcStatus(
        @Schema(description = "The OIDC settings are there, for at least one app.") boolean enabled,
        @Schema(description = "The apps OIDC sign-in is on for, `CONSOLE` before `PORTAL`; empty when off.")
                List<OidcApp> apps,
        @Schema(description = "The last OIDC sign-in that started a session; null before the first since the app "
                + "started.", nullable = true) OffsetDateTime lastSuccessAt,
        @Schema(description = "Provider-side failures since the app started: discovery, token exchange, ID token "
                + "checks.") int providerFailures,
        @Schema(description = "The last of them; null without one.", nullable = true) OidcFailure lastFailure) {}
