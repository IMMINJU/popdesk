package io.popdesk.notification;

import java.time.Instant;
import java.util.List;

/**
 * What the system status says of OIDC sign-in (OPS-3, S11-AC-30): helpdesk signs people in and implements this, so
 * the status page asks without notification reading helpdesk. Counted in memory since the app started.
 */
public interface OidcSignInState {

    /** The kinds of provider-side failure: the discovery document, the token endpoint, the ID token. */
    enum FailureKind {
        DISCOVERY,
        TOKEN,
        ID_TOKEN
    }

    /**
     * One reading. {@code apps} holds {@code CONSOLE} before {@code PORTAL} and is empty when OIDC is off;
     * {@code lastSuccessAt}, {@code lastFailureKind}, and {@code lastFailureAt} are null when there was none.
     */
    record Snapshot(boolean enabled, List<String> apps, Instant lastSuccessAt, int providerFailures,
            FailureKind lastFailureKind, Instant lastFailureAt) {}

    Snapshot snapshot();
}
