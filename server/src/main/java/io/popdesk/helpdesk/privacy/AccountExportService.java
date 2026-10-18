package io.popdesk.helpdesk.privacy;

import io.popdesk.ApiException;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.privacy.PrivacyRepository.AccountData;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The account export (PII-3, S10A-AC-01 to S10A-AC-07, design.md 0절 9 and 3절): one JSON file with the
 * account, its profile photo, the issues it reported, and the comments and attachments it wrote,
 * streamed from one read-only snapshot. The file holds no other account's ID, name, or email
 * (S10A-AC-05). Admins only; the security filter chain has checked that. Two exports run at a time,
 * counted apart from audit log exports; each holds a database connection while it streams.
 */
@Service
public class AccountExportService {

    /** How many account exports run at once. */
    static final int EXPORTS = 2;

    /** Seconds a refused client waits before it tries again. */
    static final long RETRY_AFTER = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Logger log = LoggerFactory.getLogger(AccountExportService.class);

    private final PrivacyRepository repository;
    private final AuditLog audit;
    private final Transactions transactions;
    private final TransactionTemplate reading;
    private final Clock clock;
    private final Semaphore exports = new Semaphore(EXPORTS);

    AccountExportService(PrivacyRepository repository, AuditLog audit, Transactions transactions,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        // One snapshot for the whole file: what changes while it streams does not show.
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * What a {@code HEAD} on the export checks: 404 for an account that does not exist (account 0,
     * popdesk itself, has no row), and 503 while every place is taken. It records nothing and takes no
     * place.
     */
    public void check(long accountId) {
        requireAccount(accountId);
        if (exports.availablePermits() == 0) {
            throw ApiException.busy(RETRY_AFTER);
        }
    }

    /**
     * Starts an export (S10A-AC-01, S10A-AC-06): 404 for an account that does not exist, then one of the
     * {@value #EXPORTS} places (503 when none is free), then {@code ACCOUNT_EXPORTED} by the admin with
     * the target {@code {type: ACCOUNT, accountId}} and no changes. A refused export records nothing.
     * The place is given back when {@link Export#writeTo} ends, so every export returned must be written.
     */
    public Export export(CurrentAccount admin, long accountId) {
        requireAccount(accountId);
        if (!exports.tryAcquire()) {
            throw ApiException.busy(RETRY_AFTER);
        }
        try {
            Instant startedAt = clock.instant();
            transactions.run(() -> {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("type", "ACCOUNT");
                target.put("accountId", accountId);
                audit.record(admin.actor(), AuditAction.ACCOUNT_EXPORTED, target, null);
            });
            log.info("Account {} is exported.", accountId);
            return new Export(accountId, startedAt);
        } catch (RuntimeException | Error e) {
            exports.release();
            throw e;
        }
    }

    private void requireAccount(long accountId) {
        Optional<AccountData> account = reading.execute(status -> repository.findAccount(accountId));
        if (account == null || account.isEmpty()) {
            throw ApiException.notFound();
        }
    }

    /** An export that holds one of the places until it is written. */
    public final class Export {

        private final long accountId;
        private final Instant startedAt;
        private final AtomicBoolean written = new AtomicBoolean();

        private Export(long accountId, Instant startedAt) {
            this.accountId = accountId;
            this.startedAt = startedAt;
        }

        /**
         * Streams the file (design.md 3절): {@code exportedAt}, {@code account}, {@code photo},
         * {@code issues}, {@code comments}, {@code attachments}, in that order, the lists oldest first
         * and then by ID, never holding more than a batch of rows. Gives the place back.
         */
        public void writeTo(OutputStream out) throws IOException {
            if (!written.compareAndSet(false, true)) {
                throw new IllegalStateException("An account export is written once");
            }
            try (JsonGenerator json = JSON.createGenerator(out)) {
                reading.executeWithoutResult(status -> write(json));
            } catch (JacksonException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw e;
            } finally {
                exports.release();
            }
        }

        private void write(JsonGenerator json) {
            AccountData account = repository.findAccount(accountId).orElseThrow(ApiException::notFound);
            json.writeStartObject();
            time(json, "exportedAt", startedAt);
            json.writeName("account");
            json.writeStartObject();
            json.writeNumberProperty("id", account.id());
            json.writeStringProperty("email", account.email());
            json.writeStringProperty("name", account.name());
            json.writeStringProperty("role", account.role());
            json.writeStringProperty("status", account.status());
            json.writeStringProperty("locale", account.locale());
            time(json, "createdAt", account.createdAt());
            json.writeBooleanProperty("anonymized", account.anonymized());
            json.writeEndObject();
            json.writeName("photo");
            Optional<byte[]> photo = repository.findPhoto(accountId);
            if (photo.isEmpty()) {
                json.writeNull();
            } else {
                json.writeStartObject();
                json.writeStringProperty("contentType", "image/png");
                json.writeStringProperty("base64", Base64.getEncoder().encodeToString(photo.get()));
                json.writeEndObject();
            }
            json.writeName("issues");
            json.writeStartArray();
            repository.streamIssues(accountId, issue -> {
                json.writeStartObject();
                json.writeStringProperty("key", issue.key());
                json.writeStringProperty("companyCode", issue.companyCode());
                json.writeStringProperty("title", issue.title());
                json.writeStringProperty("content", issue.content());
                json.writeStringProperty("status", issue.status());
                json.writeBooleanProperty("private", issue.privateIssue());
                time(json, "createdAt", issue.createdAt());
                time(json, "closedAt", issue.closedAt());
                time(json, "deletedAt", issue.deletedAt());
                json.writeEndObject();
            });
            json.writeEndArray();
            json.writeName("comments");
            json.writeStartArray();
            repository.streamComments(accountId, comment -> {
                json.writeStartObject();
                json.writeStringProperty("issueKey", comment.issueKey());
                json.writeStringProperty("content", comment.content());
                json.writeBooleanProperty("internal", comment.internal());
                time(json, "createdAt", comment.createdAt());
                time(json, "editedAt", comment.editedAt());
                json.writeEndObject();
            });
            json.writeEndArray();
            json.writeName("attachments");
            json.writeStartArray();
            repository.streamAttachments(accountId, attachment -> {
                json.writeStartObject();
                json.writeStringProperty("issueKey", attachment.issueKey());
                json.writeName("commentId");
                if (attachment.commentId() == null) {
                    json.writeNull();
                } else {
                    json.writeNumber(attachment.commentId());
                }
                json.writeStringProperty("fileName", attachment.fileName());
                json.writeNumberProperty("size", attachment.size());
                time(json, "createdAt", attachment.createdAt());
                json.writeEndObject();
            });
            json.writeEndArray();
            json.writeEndObject();
        }
    }

    /** A time in the form of the API (TIME-1): UTC, ISO-8601; null stays null. */
    private static void time(JsonGenerator json, String name, Instant value) {
        json.writeName(name);
        if (value == null) {
            json.writeNull();
        } else {
            json.writeString(TIME.format(value.atOffset(ZoneOffset.UTC)));
        }
    }
}
