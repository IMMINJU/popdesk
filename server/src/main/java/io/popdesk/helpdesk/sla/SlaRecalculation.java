package io.popdesk.helpdesk.sla;

import io.popdesk.InstallationZone;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.sla.SlaIssueRepository.Recalculated;
import io.popdesk.helpdesk.sla.SlaIssueRepository.Running;
import io.popdesk.notification.CleanupJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Works out the SLA due times of running clocks again after a new policy version, {@code sla-recalculate}, every
 * minute (SLA-4, S8A-AC-20, design.md 4절). The count recorded is the issues worked out again (S8A-AC-25).
 *
 * <p>First, in a transaction of its own (design.md 0절 8b): version 1 gets the installation's time zone once, and when
 * {@code POPDESK_TIMEZONE} is not the current version's zone, a new version with it and the same rules is made by
 * popdesk, without an audit entry (S8A-AC-06, S8A-AC-07), under the current version's row {@code FOR UPDATE} as an
 * admin's change. One instance runs the jobs at a time, and the version is read again under that lock, so it goes up
 * once.
 *
 * <p>Then batches of {@link #BATCH} issues, one transaction each, until none is left or {@link #BATCHES} ran: the
 * issues are locked skipping those a write holds, and each is written only while its calculation version is still
 * the one read, so the job never undoes a write and a run that stopped halfway is taken on by the next (S8A-AC-21).
 * Settled clocks and issues in the trash are left until they run again (S8A-AC-24). The batches are counted, not
 * timed: the scheduler has one thread, and the clock of a test stands still.
 */
@Component
class SlaRecalculation implements CleanupJob {

    static final String NAME = "sla-recalculate";

    /** Issues one transaction works out at most. */
    static final int BATCH = 500;

    /** Batches one run takes at most. */
    static final int BATCHES = 10;

    private final SlaPolicyRepository policyRows;
    private final SlaIssueRepository issues;
    private final SlaPolicies policies;
    private final InstallationZone zone;
    private final Transactions transactions;
    private final Clock clock;

    SlaRecalculation(SlaPolicyRepository policyRows, SlaIssueRepository issues, SlaPolicies policies,
            InstallationZone zone, Transactions transactions, Clock clock) {
        this.policyRows = policyRows;
        this.issues = issues;
        this.policies = policies;
        this.zone = zone;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public long run(Instant now) {
        transactions.run(this::compareZone);
        long recalculated = 0;
        for (int batch = 0; batch < BATCHES; batch++) {
            int[] locked = new int[1];
            recalculated += transactions.run(() -> {
                SlaVersion current = policies.version(policyRows.current());
                List<Running> running = issues.lockRunning(current.number(), BATCH);
                locked[0] = running.size();
                return issues.updateClocks(running.stream()
                        .map(r -> new Recalculated(r.id(), r.clocks().version(),
                                policies.recalculated(r.clocks(), r.priority(), current)))
                        .toList());
            });
            if (locked[0] < BATCH) {
                break;
            }
        }
        return recalculated;
    }

    /** The time zone of the installation against the current version's (S8A-AC-07, acceptance.md 5절 11). */
    private void compareZone() {
        String installation = zone.zone().getId();
        policyRows.fillFirstZone(zone.zone());
        if (installation.equals(policyRows.storedZone(policyRows.current()))) {
            return;
        }
        int current = policyRows.lockCurrent("update");
        if (installation.equals(policyRows.storedZone(current))) {
            return;
        }
        SlaVersion before = policies.version(current);
        // Read under the lock, as a change by an admin (design.md 0절 8).
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        policyRows.insert(current + 1, zone.zone(), at, null, at, before.targets(), before.hours(), before.holidays());
    }
}
