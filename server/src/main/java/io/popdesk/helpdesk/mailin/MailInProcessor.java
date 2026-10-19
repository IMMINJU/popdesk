package io.popdesk.helpdesk.mailin;

import io.popdesk.ApiException;
import io.popdesk.InstallationLocale;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.helpdesk.attachment.AttachmentService.Refusal;
import io.popdesk.helpdesk.comment.CommentService;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueService;
import io.popdesk.helpdesk.issue.IssueService.Draft;
import io.popdesk.helpdesk.issue.IssueService.NewIssue;
import io.popdesk.helpdesk.mailin.MailReceivedRepository.Received;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import io.popdesk.notification.OpsAlerts;
import io.popdesk.notification.SentMailLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handles one received email (design.md 0절 7, 8, 9): its record, the reasons to discard it (acceptance.md 2.4), and
 * then a comment on the issue it replies to, a new issue, or a notice. The issue or comment and the record's outcome
 * are committed together, in the service's own transaction (a hook), so an email is never handled twice and never
 * lost (S12-AC-30). The try is counted before, in a transaction of its own (S12-AC-09).
 *
 * <p>The services check what they check for a portal request: the customer's scope, who may comment, the limits of
 * text and files, and the rate limits (S12-AC-28). This class reads the account first, as no session filter did
 * (design.md 0절 6). The log of one email holds its record ID, outcome, and reason only (S12-AC-34); an unknown
 * sender's address is masked (S12-AC-13).
 */
@Component
class MailInProcessor {

    private static final Logger log = LoggerFactory.getLogger(MailInProcessor.class);

    /** The largest email whose contents are read (S12-AC-09): 30 MB. */
    static final long MAX_SIZE = 30L * 1024 * 1024;

    /** Tries of one email before it is quarantined (S12-AC-09). */
    static final int MAX_ATTEMPTS = 5;

    /** The least time between two notices to one account (S12-AC-29). */
    static final Duration NOTICE_GAP = Duration.ofMinutes(15);

    static final int ISSUE_CONTENT_MAX = 10_000;
    static final int COMMENT_CONTENT_MAX = 4_000;

    /** Where the email goes in the mailbox once it is handled; {@link #STAY} leaves it for the next round. */
    enum Destination {
        PROCESSED,
        QUARANTINE,
        STAY
    }

    /** One email as the round hands it over: its bytes, or its header block alone when it is over 30 MB. */
    record Incoming(byte[] raw, boolean tooLarge) {}

    private final MailInSetup setup;
    private final MailReceivedRepository records;
    private final AccountRepository accounts;
    private final CompanyRepository companies;
    private final IssueRepository issues;
    private final IssueService issueService;
    private final CommentService commentService;
    private final AttachmentService attachments;
    private final AccountRateLimit rateLimit;
    private final SentMailLookup sentMail;
    private final MailQueue mail;
    private final OpsAlerts alerts;
    private final MailInMetrics metrics;
    private final Transactions transactions;
    private final InstallationLocale installationLocale;
    private final Environment environment;
    private final Clock clock;

    /** The last notice to each account, by the app's clock; one instance (D-23), so memory is enough. */
    private final Map<Long, Instant> lastNotice = new ConcurrentHashMap<>();

    MailInProcessor(MailInSetup setup, MailReceivedRepository records, AccountRepository accounts,
            CompanyRepository companies, IssueRepository issues, IssueService issueService,
            CommentService commentService, AttachmentService attachments, AccountRateLimit rateLimit,
            SentMailLookup sentMail, MailQueue mail, OpsAlerts alerts, MailInMetrics metrics, Transactions transactions,
            InstallationLocale installationLocale, Environment environment, Clock clock) {
        this.setup = setup;
        this.records = records;
        this.accounts = accounts;
        this.companies = companies;
        this.issues = issues;
        this.issueService = issueService;
        this.commentService = commentService;
        this.attachments = attachments;
        this.rateLimit = rateLimit;
        this.sentMail = sentMail;
        this.mail = mail;
        this.alerts = alerts;
        this.metrics = metrics;
        this.transactions = transactions;
        this.installationLocale = installationLocale;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * Handles the email (design.md 0절 9) and says where it goes. An app or database error is thrown: the email
     * stays and is tried again next round; its try was counted before.
     */
    Destination handle(Incoming incoming) {
        MailMessage message = MailMessage.of(incoming.raw());
        MailMessage.Key key = message.key();
        Optional<Received> known = records.find(key.value());
        if (known.isPresent() && known.get().outcome() != null && !dmarcFailed(known.get())) {
            // Handled before; moving it failed then (S12-AC-07, S12-AC-08).
            return destinationOf(known.get().outcome());
        }
        if (known.isPresent() && known.get().outcome() == null && known.get().attempts() >= MAX_ATTEMPTS) {
            return quarantine(known.get().id(), "QUARANTINED", "FAILED", null, null);
        }
        Optional<Received> counted = transactions.run(() -> records.attempt(key.value(), key.source(), now()));
        if (counted.isEmpty()) {
            return known.map(r -> destinationOf(r.outcome())).orElse(Destination.STAY);
        }
        long id = counted.get().id();
        if (incoming.tooLarge()) {
            return quarantine(id, "QUARANTINED", "TOO_LARGE", null, null);
        }
        if (message.tooComplex()) {
            return quarantine(id, "QUARANTINED", "TOO_COMPLEX", null, null);
        }
        MailInSettings settings = setup.settings();
        String from = message.fromAddress();
        Optional<AutomatedMail.Reason> automated = AutomatedMail.reason(message::header, message.topContentType(),
                from, sendingAddress(), settings.inboundAddress());
        if (automated.isPresent()) {
            return discard(id, automated.get().name(), null);
        }
        AccountRow sender = from == null ? null : accounts.findByEmail(from).orElse(null);
        if (sender == null || sender.role() != Role.CUSTOMER || sender.status() != AccountStatus.ACTIVE
                || sender.anonymized()) {
            return discard(id, "UNKNOWN_SENDER", from);
        }
        if (AutomatedMail.dmarc(message.header("Authentication-Results")) != AutomatedMail.Dmarc.PASS) {
            metrics.unauthenticated();
        }
        return new Customer(id, message, sender).handle();
    }

    private static boolean dmarcFailed(Received r) {
        return "DISCARDED".equals(r.outcome()) && "DMARC_FAIL".equals(r.reason());
    }

    private static Destination destinationOf(String outcome) {
        return "QUARANTINED".equals(outcome) || "RATE_LIMITED".equals(outcome) ? Destination.QUARANTINE
                : Destination.PROCESSED;
    }

    /** Discards the email (acceptance.md 2절): its record keeps no sender (S12-AC-14), and nobody is told. */
    private Destination discard(long id, String reason, String from) {
        Instant now = now();
        transactions.run(() -> records.decide(id, "DISCARDED", reason, null, null, null, null, now));
        metrics.message("DISCARDED");
        if (reason.equals("UNKNOWN_SENDER")) {
            log.info("Received email {}: DISCARDED ({}), from {}.", id, reason, from == null ? "no one address"
                    : masked(from));
        } else {
            log.info("Received email {}: DISCARDED ({}).", id, reason);
        }
        return Destination.PROCESSED;
    }

    /** Quarantines the email (acceptance.md 2절) and raises {@code MAIL_IN_QUARANTINED} with it (S12-AC-37). */
    private Destination quarantine(long id, String outcome, String reason, Long senderId, String senderAddress) {
        Instant now = now();
        transactions.run(() -> {
            records.decide(id, outcome, reason, null, null, senderId, senderAddress, now);
            alerts.raise(OpsAlerts.Kind.MAIL_IN_QUARANTINED);
        });
        metrics.message(outcome);
        log.info("Received email {}: {}{}.", id, outcome, reason == null ? "" : " (" + reason + ")");
        return Destination.QUARANTINE;
    }

    /** The email of an active customer: a comment, a new issue, or a notice. */
    private final class Customer {

        private final long id;
        private final MailMessage message;
        private final AccountRow sender;
        private final Viewer viewer;
        private final String locale;

        Customer(long id, MailMessage message, AccountRow sender) {
            this.id = id;
            this.message = message;
            this.sender = sender;
            this.viewer = new Viewer(sender.id(), Role.CUSTOMER);
            this.locale = sender.locale() == null ? installationLocale.language() : sender.locale();
        }

        Destination handle() {
            Optional<IssueRow> replied = repliedTo();
            if (replied.isPresent()) {
                return comment(replied.get());
            }
            Optional<IssueKey> key = IssueKeyInSubject.key(message.subject());
            if (key.isPresent()) {
                Optional<IssueRow> issue = issues.findByKey(viewer, key.get());
                if (issue.isEmpty()) {
                    // A key the sender cannot see: no comment there, and no new issue either (S12-AC-17).
                    return noticeA("NOT_VISIBLE");
                }
                return comment(issue.get());
            }
            return newIssue();
        }

        /**
         * The issue of the latest of the email's {@code In-Reply-To} and {@code References} that popdesk sent about an
         * issue the sender sees now (S12-AC-15, S12-AC-17), in one lookup.
         */
        private Optional<IssueRow> repliedTo() {
            List<String> ids = message.references();
            if (ids.isEmpty()) {
                return Optional.empty();
            }
            Map<String, UUID> sent = sentMail.issuesOf(ids);
            for (int i = ids.size() - 1; i >= 0; i--) {
                UUID issueId = sent.get(ids.get(i));
                if (issueId != null) {
                    Optional<IssueRow> issue = issues.findById(viewer, issueId);
                    if (issue.isPresent()) {
                        return issue;
                    }
                }
            }
            return Optional.empty();
        }

        /** A reply becomes the sender's public comment (S12-AC-15, S12-AC-16), or notice B (S12-AC-18). */
        private Destination comment(IssueRow issue) {
            Files files = files(issue.id());
            try {
                List<UUID> pending = List.of();
                try {
                    if (!files.kept().isEmpty()) {
                        attachments.countFiling(viewer, files.kept());
                    }
                    String content = content(QuoteTrim.trim(message.body()), files.leftOut(), COMMENT_CONTENT_MAX);
                    pending = files.kept().isEmpty() ? List.of() : attachments.writeFiling(viewer, files.kept());
                    List<UUID> stored = pending;
                    Instant now = now();
                    long[] commentId = {0};
                    commentService.add(viewer, issue.key(), content, false, null, null, c -> {
                        commentId[0] = c;
                        attachments.storeOnComment(stored, issue.id(), c, viewer.accountId());
                        records.decide(id, "COMMENT", null, issue.id(), c, sender.id(), sender.email(), now);
                    });
                    metrics.message("COMMENT");
                    log.info("Received email {}: COMMENT {} on issue {}.", id, commentId[0], issue.id());
                    return Destination.PROCESSED;
                } catch (ApiException e) {
                    attachments.discardEnded(pending);
                    if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
                        return rateLimited();
                    }
                    if (e.code().equals("ISSUE_CLOSED")) {
                        return noticeB(issue, "CLOSED", MailKind.MAIL_IN_NOTICE_CLOSED);
                    }
                    if (e.status() == HttpStatus.FORBIDDEN) {
                        return noticeB(issue, "NOT_ALLOWED", MailKind.MAIL_IN_NOTICE_NOT_ALLOWED);
                    }
                    throw e;
                } catch (RuntimeException e) {
                    attachments.discardEnded(pending);
                    throw e;
                }
            } finally {
                files.delete();
            }
        }

        /** A mail that is no reply becomes the sender's new issue (S12-AC-19, S12-AC-20), or notice A (S12-AC-21). */
        private Destination newIssue() {
            Optional<CompanyRow> company = company();
            if (company.isEmpty()) {
                return noticeA("NO_COMPANY");
            }
            Files files = files(null);
            try {
                List<UUID> pending = List.of();
                try {
                    if (!files.kept().isEmpty()) {
                        attachments.countFiling(viewer, files.kept());
                    }
                    String title = message.title();
                    String content = content(message.body(), files.leftOut(), ISSUE_CONTENT_MAX);
                    Draft draft = issueService.prepare(viewer, new NewIssue(company.get().code(),
                            title.isEmpty() ? text("(제목 없음)", "(no subject)") : title, content, null, false, null),
                            null, IssueService.CHANNEL_EMAIL).draft();
                    pending = files.kept().isEmpty() ? List.of() : attachments.writeFiling(viewer, files.kept());
                    List<UUID> stored = pending;
                    Instant now = now();
                    issueService.file(viewer, draft, () -> {
                        attachments.storeFiled(stored, draft.id());
                        records.decide(id, "ISSUE", null, draft.id(), null, sender.id(), sender.email(), now);
                    });
                    if (!stored.isEmpty()) {
                        attachments.reviewQuota();
                    }
                    metrics.message("ISSUE");
                    log.info("Received email {}: ISSUE {}.", id, draft.id());
                    return Destination.PROCESSED;
                } catch (ApiException e) {
                    attachments.discardEnded(pending);
                    if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
                        return rateLimited();
                    }
                    throw e;
                } catch (RuntimeException e) {
                    attachments.discardEnded(pending);
                    throw e;
                }
            } finally {
                files.delete();
            }
        }

        /**
         * The company of a new issue (S12-AC-19, S12-AC-21): the {@code +code} of the address the email was sent to,
         * else the sender's one company; it must be one the sender belongs to, then active. Belonging is looked at
         * before the status, and every way of having none is the same empty.
         */
        private Optional<CompanyRow> company() {
            List<CompanyRow> own = companies.listOwn(viewer);
            Optional<String> code = InboundAddress.code(message.toAndCc(), setup.settings().inboundAddress());
            Optional<CompanyRow> chosen;
            if (code.isPresent()) {
                chosen = own.stream().filter(c -> c.code().equals(code.get())).findFirst();
            } else {
                chosen = own.size() == 1 ? Optional.of(own.get(0)) : Optional.empty();
            }
            return chosen.filter(c -> c.status() == CompanyStatus.ACTIVE);
        }

        /**
         * Notice A (S12-AC-17, S12-AC-21): counted as one filing (S12-AC-28), then the outcome and the queued email in
         * one transaction.
         */
        private Destination noticeA(String reason) {
            try {
                rateLimit.count(AccountRateLimit.Action.ISSUE, viewer.accountId());
            } catch (ApiException e) {
                if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
                    return rateLimited();
                }
                throw e;
            }
            return notice(reason, MailKind.MAIL_IN_NOTICE_NO_ISSUE, null);
        }

        /** Notice B (S12-AC-18): the comment service counted the try as a comment already. */
        private Destination noticeB(IssueRow issue, String reason, MailKind kind) {
            return notice(reason, kind, issue.id());
        }

        /**
         * The outcome {@code NOTICE} and the notice to the account itself, at its registered address (S12-AC-29); no
         * more than one notice in 15 minutes, the outcome alike.
         */
        private Destination notice(String reason, MailKind kind, UUID issueId) {
            Instant now = now();
            Instant last = lastNotice.get(sender.id());
            boolean send = last == null || !now.isBefore(last.plus(NOTICE_GAP));
            transactions.run(() -> {
                records.decide(id, "NOTICE", reason, null, null, sender.id(), sender.email(), now);
                if (send) {
                    mail.queue(kind, sender.id(), issueId, null);
                }
            });
            if (send) {
                lastNotice.put(sender.id(), now);
            }
            metrics.message("NOTICE");
            log.info("Received email {}: NOTICE ({}){}.", id, reason, send ? "" : ", no email within 15 minutes");
            return Destination.PROCESSED;
        }

        private Destination rateLimited() {
            return quarantine(id, "RATE_LIMITED", null, sender.id(), sender.email());
        }

        /** The named parts as files, and a line for each one left out (S12-AC-25, S12-AC-26). */
        private Files files(UUID issueId) {
            List<MailMessage.NamedPart> parts = message.namedParts();
            List<MailPartFile> all = new ArrayList<>();
            try {
                for (int i = 0; i < parts.size(); i++) {
                    String name = MailMessage.fileName(parts.get(i).name());
                    all.add(MailMessage.file(parts.get(i), name.isEmpty() ? "attachment-" + (i + 1) : name));
                }
                List<Refusal> refusals = attachments.sortOutMail(List.copyOf(all), issueId);
                List<MultipartFile> kept = new ArrayList<>();
                List<String> leftOut = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    if (refusals.get(i) == null) {
                        kept.add(all.get(i));
                    } else {
                        leftOut.add(leftOutLine(all.get(i).getOriginalFilename(), refusals.get(i)));
                    }
                }
                return new Files(all, kept, leftOut);
            } catch (RuntimeException e) {
                all.forEach(MailPartFile::delete);
                throw e;
            }
        }

        private String leftOutLine(String name, Refusal refusal) {
            String shown = name.codePointCount(0, name.length()) <= 255 ? name
                    : name.substring(0, name.offsetByCodePoints(0, 255));
            String why = switch (refusal) {
                case EMPTY -> text("빈 파일", "empty file");
                case TOO_LARGE -> text("10MB 넘음", "over 10 MB");
                case TOO_MANY -> text("개수 넘음", "too many files");
                case ISSUE_TOTAL -> text("문의의 첨부 합계 넘음", "over the issue's total");
                case QUOTA -> text("저장 한도 넘음", "over the storage quota");
                case NAME_TOO_LONG -> text("이름이 김", "name too long");
            };
            return text("빠진 첨부: ", "Attachment left out: ") + shown + " (" + why + ")";
        }

        /** The content in its limit (S12-AC-22, S12-AC-24), {@code (no content)} for an empty body. */
        private String content(String body, List<String> leftOut, int limit) {
            String text = body.isBlank() ? text("(내용 없음)", "(no content)") : body;
            return BodyFit.fit(text, leftOut, limit,
                    text("(메일이 길어 앞부분만 옮겼습니다.)", "(The email was too long; only its beginning was kept.)"));
        }

        private String text(String korean, String english) {
            return "ko".equalsIgnoreCase(locale) ? korean : english;
        }
    }

    /** The files of one email: all of them as temporary files, those kept, and the lines of those left out. */
    private record Files(List<MailPartFile> all, List<MultipartFile> kept, List<String> leftOut) {

        void delete() {
            all.forEach(MailPartFile::delete);
        }
    }

    /**
     * The address popdesk sends from, as the mail channel picks it: {@code MAIL_FROM}, else {@code SMTP_USER} when it
     * is an address, else popdesk at the console host; its addr-spec (S12-AC-11).
     */
    private String sendingAddress() {
        String from = environment.getProperty("MAIL_FROM");
        if (from == null || from.isBlank()) {
            String user = environment.getProperty("SMTP_USER");
            if (user != null && user.contains("@")) {
                from = user;
            } else {
                String host = environment.getProperty("POPDESK_CONSOLE_HOST");
                from = "popdesk@" + (host == null ? "localhost" : host);
            }
        }
        int open = from.lastIndexOf('<');
        int close = from.lastIndexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).strip() : from.strip();
    }

    /** The address with everything after its first character masked: {@code a***@example.com} (PII-2). */
    static String masked(String email) {
        int at = email.lastIndexOf('@');
        return email.isEmpty() ? "***" : email.charAt(0) + "***" + (at < 0 ? "" : email.substring(at));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
