package io.popdesk.helpdesk.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The audit log (AUDIT-1, S0-AC-83). An entry is written in the transaction of the change it records,
 * so both are kept or neither is. The person is always {@code {accountId, name, email}}. Never pass a
 * password, a token, or a link.
 *
 * <p>{@link #record} only keeps the entry with the transaction; its time is taken then. Just before
 * the transaction commits, all its entries join the hash chain at once ({@link AuditChainRepository#append}),
 * so the chain's head row is locked last and only for the hashing and the inserts (S1B-AC-09 to
 * S1B-AC-11). Until then the entry is not in the database: code in the same transaction cannot read it
 * back. A transaction that rolls back takes its entries with it.
 */
@Service
public class AuditLog {

    /** The account ID that stands for popdesk itself (the first admin, commands). */
    public static final long SYSTEM_ACCOUNT_ID = 0;

    /** The person who did something. {@code name} and {@code email} are null for the system. */
    public record Actor(long accountId, String name, String email) {

        public static final Actor SYSTEM = new Actor(SYSTEM_ACCOUNT_ID, null, null);
    }

    private final AuditRepository repository;
    private final Clock clock;

    AuditLog(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Records an action done by popdesk itself: the person is account 0 without name or email. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBySystem(AuditAction action, Map<String, ?> target, Map<String, ?> changes) {
        record(Actor.SYSTEM, action, target, changes);
    }

    /**
     * Records an action in the caller's transaction. A number in {@code target} or {@code changes}
     * must be an {@code Integer}, {@code Long} or {@code Short}; any other is an
     * {@link IllegalArgumentException}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, AuditAction action, Map<String, ?> target, Map<String, ?> changes) {
        // The column keeps microseconds (timestamptz).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        pending().add(AuditChain.values(action, actor.accountId(), actor.name(), actor.email(), target, changes,
                now));
    }

    /** The entries of the current transaction; the first call registers them for its commit. */
    private Pending pending() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending && pending.log() == this) {
                return pending;
            }
        }
        Pending pending = new Pending();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    /**
     * The entries one transaction recorded. They live with the transaction's synchronizations, so a
     * retry in a new transaction ({@code Transactions.run}) starts with none.
     */
    private final class Pending implements TransactionSynchronization {

        private final List<AuditChain.Values> entries = new ArrayList<>();
        private boolean appended;

        AuditLog log() {
            return AuditLog.this;
        }

        void add(AuditChain.Values entry) {
            if (appended) {
                throw new IllegalStateException("An audit entry was recorded after the transaction's entries "
                        + "joined the chain");
            }
            entries.add(entry);
        }

        /** After every other synchronization: nothing is locked once the chain's head row is. */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            appended = true;
            repository.append(entries);
        }
    }
}
