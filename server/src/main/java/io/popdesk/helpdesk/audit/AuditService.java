package io.popdesk.helpdesk.audit;

import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.audit.AuditRepository.Chain;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading the audit log: pages (AUDIT-3), exports (AUDIT-8), and the integrity check of its hash chain
 * (AUDIT-7). Admins only; the security filter chain has checked that. Nothing here changes an entry,
 * and only {@link #removeOlderThan}, for the retention period, removes any.
 */
@Service
public class AuditService {

    /** How many exports run at once (S1B-AC-28). Each holds a database connection while it streams. */
    static final int EXPORTS = 2;

    /** How many entries the retention period removes in one transaction (B7-AC-01). */
    static final int RETENTION_BATCH = 1000;

    /** Seconds a refused client waits before it tries again. */
    static final long RETRY_AFTER = 1;

    private static final byte[] LINE_FEED = {'\n'};
    private static final byte[] CRLF = {'\r', '\n'};

    /** A byte order mark, so a spreadsheet reads the CSV as UTF-8. */
    private static final byte[] UTF8_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The file format of an export. */
    public enum Format {
        CSV,
        JSONL
    }

    /**
     * The integrity check's answer. When the chain does not hold, either {@code brokenEntry} is the
     * first entry that does not follow the one before it, or it is null and every entry that is left
     * holds but the last one is not the recorded end of the chain.
     */
    public record Verification(boolean valid, long checkedCount, AuditRow brokenEntry) {}

    private final AuditRepository repository;
    private final AuditLog audit;
    private final AuditRechain rechain;
    private final Transactions transactions;
    private final TransactionTemplate reading;
    private final TransactionTemplate verifying;
    private final Semaphore exports = new Semaphore(EXPORTS);
    private final AtomicBoolean verificationRunning = new AtomicBoolean();

    AuditService(AuditRepository repository, AuditLog audit, AuditRechain rechain, Transactions transactions,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.audit = audit;
        this.rechain = rechain;
        this.transactions = transactions;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        // One snapshot for the whole check: entries appended or removed while it runs do not show.
        this.verifying = new TransactionTemplate(transactionManager);
        this.verifying.setReadOnly(true);
        this.verifying.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** One page, newest first; entries of the same time by {@code seq}, highest first (S1B-AC-01). */
    @Transactional(readOnly = true)
    public Paged<AuditRow> list(AuditFilter filter, int page, int size) {
        filter.check();
        PageRequest request = PageRequest.of(page, size);
        return request.of(repository.list(filter, request.offset(), request.size()), repository.count(filter, null));
    }

    /** What a {@code HEAD} on the export checks: the input, and 503 while every place is taken. */
    public void checkExport(AuditFilter filter) {
        filter.check();
        if (exports.availablePermits() == 0) {
            throw ApiException.busy(RETRY_AFTER);
        }
    }

    /**
     * Starts an export (S1B-AC-23, S1B-AC-26): takes one of the {@value #EXPORTS} places (503 when
     * none is free), then records {@code AUDIT_EXPORTED} with the number of entries found, and returns
     * the export to write. A refused export records nothing. The file holds the entries up to the
     * chain's head as it was read here, so it never holds its own entry. The place is given back when
     * {@link Export#writeTo} ends, so every export returned must be written.
     */
    public Export export(AuditLog.Actor admin, Format format, AuditFilter filter) {
        filter.check();
        if (!exports.tryAcquire()) {
            throw ApiException.busy(RETRY_AFTER);
        }
        try {
            long headSeq = transactions.run(() -> {
                long head = repository.chain().headSeq();
                // Entries up to the head are all committed, so this count is the number of lines.
                long count = repository.count(filter, head);
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("format", format.name());
                changes.put("count", count);
                changes.put("filter", filter.sent());
                audit.record(admin, AuditAction.AUDIT_EXPORTED, Map.of("type", "AUDIT_LOG"), changes);
                return head;
            });
            return new Export(format, filter, headSeq);
        } catch (RuntimeException | Error e) {
            exports.release();
            throw e;
        }
    }

    /** An export that holds one of the places until it is written. */
    public final class Export {

        private final Format format;
        private final AuditFilter filter;
        private final long headSeq;
        private final AtomicBoolean written = new AtomicBoolean();

        private Export(Format format, AuditFilter filter, long headSeq) {
            this.format = format;
            this.filter = filter;
            this.headSeq = headSeq;
        }

        /**
         * Streams the file in chain order ({@code seq}, lowest first) from a cursor, never holding
         * more than a batch of rows (S1B-AC-27), and gives the place back.
         */
        public void writeTo(OutputStream out) throws IOException {
            if (!written.compareAndSet(false, true)) {
                throw new IllegalStateException("An audit export is written once");
            }
            try {
                if (format == Format.CSV) {
                    out.write(UTF8_MARK);
                    out.write(AuditExportLines.csvHeader().getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF);
                }
                reading.executeWithoutResult(status -> repository.stream(filter, headSeq, row -> write(out, row)));
                out.flush();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            } finally {
                exports.release();
            }
        }

        private void write(OutputStream out, AuditRow row) {
            try {
                if (format == Format.CSV) {
                    out.write(AuditExportLines.csvLine(row).getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF);
                } else {
                    out.write(AuditExportLines.jsonLine(row).getBytes(StandardCharsets.UTF_8));
                    out.write(LINE_FEED);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Computes the chain again from where it starts (S1B-AC-14 to S1B-AC-17, S1B-AC-42): each entry's
     * {@code seq} follows the one before, its previous hash is that entry's hash, and its hash is the
     * one computed from its values; then the last entry is the recorded head. One check runs at a time
     * (S1B-AC-18): the place is taken before anything is read, and a second caller gets 409.
     */
    public Verification verify() {
        if (!verificationRunning.compareAndSet(false, true)) {
            throw ApiException.conflict("AUDIT_VERIFICATION_RUNNING", "A check of the audit log is already running.");
        }
        try {
            return verifying.execute(status -> {
                Chain chain = repository.chain();
                Walk walk = new Walk(chain.anchorSeq(), chain.anchorHash());
                repository.stream(new AuditFilter(null, null, null, null, null, null, null), null, walk::check);
                boolean valid = walk.broken == null && walk.seq == chain.headSeq()
                        && walk.hash.equals(chain.headHash());
                return new Verification(valid, walk.checked, walk.broken);
            });
        } finally {
            verificationRunning.set(false);
        }
    }

    /**
     * Removes the entries that happened before {@code cutoff}, from the front of the chain and only
     * as far as they follow one another (S1B-AC-19, S1B-AC-21): an old entry behind a newer one stays
     * until that one is old too, so the chain never loses its middle. An entry can be behind a newer
     * one because its time is taken when it is recorded and its place when its transaction commits.
     * Where to stop is found once, at the start (B7-AC-01, B7-AC-05): entries appended later are not
     * that old, and an anonymization changes hashes, not places or times.
     *
     * <p>Up to {@value #RETENTION_BATCH} entries at a time, each batch in a transaction of its own: the
     * chain's row is locked first, as appending locks it, then the batch's last entry is read, the
     * entries go, and the chain's starting point becomes that entry's place and hash. Appending waits
     * for one batch, never the whole run. The integrity check reads a snapshot, so it sees the chain
     * between two batches, never inside one. When a batch fails, the ones before it stay removed and the
     * run fails (B7-AC-04); the next run goes on from where the chain then starts.
     *
     * <p>Each batch waits while an anonymization computes the chain again ({@link AuditRechain},
     * S10A-AC-22): that one walks from an entry it found without locking the chain, so the front must
     * not move meanwhile. An anonymization that comes during a batch goes before the next (B7-AC-06).
     *
     * @return how many entries were removed
     */
    public long removeOlderThan(Instant cutoff) {
        Optional<AuditRow> last = transactions.run(() -> repository.lastOfFrontBefore(cutoff));
        if (last.isEmpty()) {
            return 0;
        }
        long end = last.get().seq();
        long removed = 0;
        while (true) {
            Long batch = rechain.waiting(() -> transactions.run(() -> removeBatch(end)));
            if (batch == null) {
                return removed;
            }
            removed += batch;
        }
    }

    /** One batch of {@link #removeOlderThan}: how many entries it removed, or null when none was left. */
    private Long removeBatch(long end) {
        Chain chain = repository.lockChain();
        if (chain.anchorSeq() >= end) {
            return null;
        }
        Optional<AuditRow> boundary = repository.lastUpTo(Math.min(end, chain.anchorSeq() + RETENTION_BATCH));
        if (boundary.isEmpty() || boundary.get().seq() <= chain.anchorSeq()) {
            return null;
        }
        long removed = repository.deleteUpTo(boundary.get().seq());
        repository.updateAnchor(boundary.get().seq(), boundary.get().hash());
        return removed;
    }

    /** The walk along the chain: the entry before, and the first entry that does not follow. */
    private static final class Walk {

        private long seq;
        private String hash;
        private long checked;
        private AuditRow broken;

        Walk(long anchorSeq, String anchorHash) {
            this.seq = anchorSeq;
            this.hash = anchorHash;
        }

        void check(AuditRow row) {
            if (broken != null) {
                return;
            }
            if (row.seq() != seq + 1 || !hash.equals(row.prevHash())
                    || !AuditChain.holds(row.prevHash(), row.values(), row.hash())) {
                broken = row;
                return;
            }
            seq = row.seq();
            hash = row.hash();
            checked++;
        }
    }
}
