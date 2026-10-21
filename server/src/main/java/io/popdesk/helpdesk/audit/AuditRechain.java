package io.popdesk.helpdesk.audit;

import io.popdesk.ApiException;
import io.popdesk.helpdesk.audit.AuditChainRepository.Walk;
import io.popdesk.notification.AuditCopies;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the audit chain again after an anonymization changed the person fields of entries (AUDIT-6,
 * PII-5, ADR D-62, design.md 0절 2). In the anonymization's one transaction, in two parts:
 *
 * <ol>
 *   <li>{@link #start}: without locking the chain's head, from the person's first entry up to the head
 *       as it is then, a batch at a time ({@link AuditChainRepository#rechain}). Appending goes on
 *       meanwhile: it locks the head row and touches no older row.
 *   <li>{@link #finish}, the last thing the transaction does: locks the head row as appending does,
 *       goes on over the entries appended since the start, moves the head's hash, and appends
 *       {@code AUDIT_RECHAINED} right after the last of them. The head is held for those entries only,
 *       and until the commit.
 * </ol>
 *
 * <p>The two parts run while this class's lock is held ({@link #alone}). The retention period takes the
 * same lock for each batch it removes ({@link AuditService#removeOlderThan}), so the start of the chain
 * stays where it is while the first part runs. One anonymization runs at a time; one that comes while a
 * batch runs waits for that batch, and the lock is fair, so it goes before the next batch (B7-AC-06).
 * The app is one instance (ADR D-23).
 *
 * <p>Both parts check every entry they pass as it was, with the integrity check's function
 * ({@link AuditChain#holds}), and the second part the head too. A break is refused with 409
 * {@code AUDIT_CHAIN_BROKEN} and the transaction rolls back, so an anonymization never covers a change
 * someone made in the database with new hashes (S10A-AC-37, acceptance.md 5절 11).
 */
@Service
public class AuditRechain {

    private static final Logger log = LoggerFactory.getLogger(AuditRechain.class);

    /** Fair: a thread that waits gets it before one that asks later, so batches let a waiting one in. */
    private final ReentrantLock lock = new ReentrantLock(true);

    /** Taken by an anonymization from before it waits for the lock until it lets it go. */
    private final AtomicBoolean anonymizing = new AtomicBoolean();
    private final AuditRepository repository;
    private final AuditCopies copies;
    private final Clock clock;

    AuditRechain(AuditRepository repository, AuditCopies copies, Clock clock) {
        this.repository = repository;
        this.copies = copies;
        this.clock = clock;
    }

    /**
     * A recalculation between its two parts: where it stands, and whose values it puts in. Get one from
     * {@link #start} and give it to {@link #finish} in the same transaction.
     */
    public static final class Recalculation {

        private final Walk walk;
        private final long accountId;
        private final String name;
        private final String email;

        private Recalculation(Walk walk, long accountId, String name, String email) {
            this.walk = walk;
            this.accountId = accountId;
            this.name = name;
            this.email = email;
        }
    }

    /**
     * Runs {@code work} while holding the lock of the recalculation, which the retention period shares;
     * throws what {@code busy} gives, without running it, while another anonymization runs or waits
     * (S10A-AC-21). While a batch of the retention period holds the lock it waits for that batch
     * (B7-AC-06). Call it outside any transaction: the lock is let go after the work's transaction
     * ended.
     */
    public <T> T alone(Supplier<T> work, Supplier<? extends RuntimeException> busy) {
        if (!anonymizing.compareAndSet(false, true)) {
            throw busy.get();
        }
        try {
            lock.lock();
            try {
                return work.get();
            } finally {
                lock.unlock();
            }
        } finally {
            anonymizing.set(false);
        }
    }

    /**
     * Runs {@code work} once the lock is free, holding it meanwhile: for one batch of the retention
     * period. An anonymization that waits for the batch gets the lock before the next batch.
     */
    <T> T waiting(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The first part: puts the anonymous values of the account into its entries up to the chain's head
     * as it is now, and computes the hashes from the first changed entry on (S10A-AC-17, S10A-AC-18).
     * Locks the rows it changes and not the head row. Call it inside {@link #alone}.
     *
     * @param name the anonymous name
     * @param email the anonymous email
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recalculation start(long accountId, String name, String email) {
        requireLock();
        return new Recalculation(repository.rechainToHead(accountId, name, email), accountId, name, email);
    }

    /**
     * The second part, the last write of the transaction: locks the head row, goes on over the entries
     * appended since {@link #start}, and when any entry changed, moves the head's hash and appends
     * {@code AUDIT_RECHAINED} by {@code actor}: target {@code {type: AUDIT_LOG}}, {@code fromSeq} and
     * {@code toSeq}, the first changed entry and the last one computed again, and {@code headHash}, the
     * hash of {@code toSeq} before and after (S10A-AC-18). When no entry changed, nothing is computed
     * again and nothing is appended. Entries the transaction recorded join the chain after it, at the
     * commit, and the head stays locked until then.
     *
     * <p>The first part's break is refused here, after the account's checks, so their 400s come first.
     *
     * @throws ApiException 409 {@code AUDIT_CHAIN_BROKEN} when an entry from the first one computed again
     *     to the head, or the head, does not hold; the caller's transaction must roll back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(Recalculation recalculation, AuditLog.Actor actor) {
        requireLock();
        Walk walk = recalculation.walk;
        refuseBroken(walk);
        String headBefore = repository.rechainFromHead(walk, recalculation.accountId, recalculation.name,
                recalculation.email);
        refuseBroken(walk);
        if (walk.fromSeq == null) {
            return;
        }
        Map<String, Object> headHash = new LinkedHashMap<>();
        headHash.put("from", headBefore);
        headHash.put("to", walk.newHash);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("fromSeq", walk.fromSeq);
        changes.put("toSeq", walk.seq);
        changes.put("headHash", headHash);
        repository.append(List.of(AuditChain.values(AuditAction.AUDIT_RECHAINED, actor.accountId(), actor.name(),
                actor.email(), Map.of("type", "AUDIT_LOG"), changes, clock.instant().truncatedTo(ChronoUnit.MICROS))),
                copies.enabled());
    }

    private static void refuseBroken(Walk walk) {
        if (walk.brokenSeq != null) {
            log.warn("The audit chain is broken at seq {}; the anonymization is refused and rolled back.",
                    walk.brokenSeq);
            throw ApiException.auditChainBroken(walk.brokenSeq);
        }
    }

    private void requireLock() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("The audit chain is computed again only inside AuditRechain.alone");
        }
    }
}
