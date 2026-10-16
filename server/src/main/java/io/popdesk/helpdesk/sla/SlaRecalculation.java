package io.popdesk.helpdesk.sla;

import io.popdesk.InstallationZone;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.sla.SlaIssueRepository.Recalculated;
import io.popdesk.helpdesk.sla.SlaIssueRepository.Running;
import io.popdesk.helpdesk.sla.SlaReportRepository.Counts;
import io.popdesk.helpdesk.sla.SlaReportRepository.Open;
import io.popdesk.notification.AdminInbox;
import io.popdesk.notification.CleanupJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Works out the SLA due times of running clocks again after a new policy version, {@code sla-recalculate}, every
 * minute (SLA-4, S8A-AC-20, design.md 4절), and tells staff of the goals that came into warning or ran out as time
 * went by (SLA-5, slice 8b). The count recorded is the issues worked out again (S8A-AC-25). One run, in this order
 * (slice 8b design.md 2절):
 *
 * <ol>
 *   <li>In a transaction of its own (design.md 0절 8b): version 1 gets the installation's time zone once, and when
 *       {@code POPDESK_TIMEZONE} is not the current version's zone, a new version with it and the same rules is
 *       made by popdesk, without an audit entry (S8A-AC-06, S8A-AC-07), under the current version's row
 *       {@code FOR UPDATE} as an admin's change. One instance runs the jobs at a time, and the version is read again
 *       under that lock, so it goes up once.
 *   <li>The alerts ({@link SlaAlertStep}). A failure is kept and the run goes on (slice 8b design.md 0절 11).
 *   <li>Batches of {@link #BATCH} issues, one transaction each, until none is left or {@link #BATCHES} ran: the
 *       issues are locked skipping those a write holds, and each is written only while its calculation version is
 *       still the one read, so the job never undoes a write and a run that stopped halfway is taken on by the next
 *       (S8A-AC-21). Settled clocks and issues in the trash are left until they run again (S8A-AC-24). Each issue
 *       written is compared before and after ({@link SlaAlerts#recalculated}), and the batch adds what changed to
 *       the open summary row after the issue rows (slice 8b design.md 0절 8, 10). The batches are counted, not
 *       timed: the scheduler has one thread, and the clock of a test stands still.
 *   <li>When no running issue of an older version is left, the summary closes in a transaction of its own: the
 *       current version's row {@code FOR KEY SHARE}, so a policy change waits until the summary is closed, then the
 *       open summary row {@code FOR UPDATE}, then the count; every {@code ACTIVE} admin gets one
 *       {@code SLA_RECALCULATED} item unless its three counts are 0 (S8B-AC-14, slice 8b design.md 0절 9).
 * </ol>
 *
 * A run whose alerts failed fails after the rest, so it is not recorded and runs again a minute later (S8B-AC-28).
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
    private final SlaReportRepository reports;
    private final SlaPolicies policies;
    private final SlaAlertStep alerts;
    private final AdminInbox adminInbox;
    private final InstallationZone zone;
    private final Transactions transactions;
    private final Clock clock;

    SlaRecalculation(SlaPolicyRepository policyRows, SlaIssueRepository issues, SlaReportRepository reports,
            SlaPolicies policies, SlaAlertStep alerts, AdminInbox adminInbox, InstallationZone zone,
            Transactions transactions, Clock clock) {
        this.policyRows = policyRows;
        this.issues = issues;
        this.reports = reports;
        this.policies = policies;
        this.alerts = alerts;
        this.adminInbox = adminInbox;
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
        RuntimeException alertsFailed = null;
        try {
            alerts.run();
        } catch (RuntimeException e) {
            alertsFailed = e;
        }
        long recalculated;
        try {
            recalculated = recalculate();
            transactions.run(this::closeSummary);
        } catch (RuntimeException e) {
            if (alertsFailed != null) {
                e.addSuppressed(alertsFailed);
            }
            throw e;
        }
        if (alertsFailed != null) {
            throw alertsFailed;
        }
        return recalculated;
    }

    /** Step 3, the batches; gives the issues worked out again. */
    private long recalculate() {
        // Read once for the step, to the microsecond as the time of a run.
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long recalculated = 0;
        for (int batch = 0; batch < BATCHES; batch++) {
            int[] locked = new int[1];
            recalculated += transactions.run(() -> {
                SlaVersion current = policies.version(policyRows.current());
                List<Running> running = issues.lockRunning(current.number(), BATCH);
                locked[0] = running.size();
                Map<UUID, SlaAlerts.Change> changes = new HashMap<>();
                List<Recalculated> worked = new ArrayList<>();
                for (Running r : running) {
                    IssueClocks after = policies.recalculated(r.clocks(), r.priority(), current);
                    SlaAlerts.Recalculated compared = SlaAlerts.recalculated(r.clocks().firstResponse(),
                            r.clocks().resolution(), after.firstResponse(), after.resolution(),
                            r.clocks().firstResponseAlerted(), r.clocks().resolutionAlerted(), at);
                    changes.put(r.id(), compared.change());
                    worked.add(new Recalculated(r.id(), r.clocks().version(),
                            after.marked(compared.firstResponse(), compared.resolution())));
                }
                List<UUID> written = issues.updateClocks(worked);
                Counts counts = Counts.NONE;
                for (UUID id : written) {
                    counts = counts.plus(changes.get(id));
                }
                reports.add(counts, at);
                return written.size();
            });
            if (locked[0] < BATCH) {
                break;
            }
        }
        return recalculated;
    }

    /** Step 4: closes the open summary once no running issue of an older version is left. */
    private void closeSummary() {
        int current = policyRows.lockCurrent("key share");
        Optional<Open> open = reports.lockOpen();
        if (open.isEmpty() || issues.countRunningBefore(current) > 0) {
            return;
        }
        reports.close(open.get().id(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        Counts counts = open.get().counts();
        if (!counts.none()) {
            adminInbox.slaRecalculated(counts.becameBreached(), counts.becameWarning(), counts.released());
        }
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
