package io.popdesk.helpdesk.account;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Team 1 and the first admin, each in one transaction (S0-AC-01). */
@Service
class FirstAdminService {

    static final String TEAM_NAME = "popdesk";

    private final FirstAdminRepository repository;
    private final AuditLog audit;
    private final Clock clock;

    FirstAdminService(FirstAdminRepository repository, AuditLog audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void createTeamIfMissing() {
        repository.insertTeamIfMissing(TEAM_NAME, now());
    }

    @Transactional(readOnly = true)
    public boolean adminExists() {
        return repository.adminExists();
    }

    /**
     * "Create the admin if there is none", with its audit row in the same transaction. The person of
     * the audit row is the system. Returns the new account's ID, or empty when an admin existed.
     */
    @Transactional
    public Optional<Long> createAdminIfNone(String email, String name, String passwordHash, String locale) {
        Optional<Long> created = repository.insertAdminIfNone(email, name, passwordHash, locale, now());
        created.ifPresent(id -> {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("type", "ACCOUNT");
            target.put("accountId", id);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("email", email);
            changes.put("name", name);
            changes.put("role", Role.ADMIN.name());
            audit.recordBySystem(AuditAction.FIRST_ADMIN_CREATED, target, changes);
        });
        return created;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
