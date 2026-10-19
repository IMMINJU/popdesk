package io.popdesk.helpdesk.account;

import io.popdesk.notification.OidcSignInState;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The OIDC part of the system status (S11-AC-30, design.md 0절 8), counted in memory: the last sign-in that started a
 * session, and the provider-side failures since the app started (the discovery document, the token endpoint, the ID
 * token). Failures on the account's side are not counted, nor a start refused from a remembered discovery failure
 * without reaching the provider. Times are of the app's clock.
 */
@Component
class OidcStatus implements OidcSignInState {

    private final OidcSetup setup;
    private final Clock clock;

    private Instant lastSuccessAt;
    private int providerFailures;
    private FailureKind lastFailureKind;
    private Instant lastFailureAt;

    OidcStatus(OidcSetup setup, Clock clock) {
        this.setup = setup;
        this.clock = clock;
    }

    /** An OIDC sign-in started a session now. */
    synchronized void signedIn() {
        lastSuccessAt = clock.instant();
    }

    /** The provider failed now, in this way. */
    synchronized void failed(FailureKind kind) {
        providerFailures++;
        lastFailureKind = kind;
        lastFailureAt = clock.instant();
    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(setup.settings().enabled(), setup.settings().apps().stream().map(Enum::name).toList(),
                lastSuccessAt, providerFailures, lastFailureKind, lastFailureAt);
    }
}
