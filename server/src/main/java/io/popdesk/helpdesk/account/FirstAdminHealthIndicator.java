package io.popdesk.helpdesk.account;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Down while the app waits for a first admin or a valid {@code POPDESK_LOCALE} (S0-AC-02). Part of the readiness group
 * (application.yml), not of liveness: the process is alive and should not be restarted.
 */
@Component
class FirstAdminHealthIndicator implements HealthIndicator {

    private final FirstAdminSetup setup;

    FirstAdminHealthIndicator(FirstAdminSetup setup) {
        this.setup = setup;
    }

    @Override
    public Health health() {
        return setup.setupRequired() ? Health.down().withDetail("reason", "SETUP_REQUIRED").build() : Health.up().build();
    }
}
