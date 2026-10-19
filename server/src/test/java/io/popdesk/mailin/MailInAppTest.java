package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import jakarta.mail.Flags;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The app of every server test that receives email (slice 12): "connected" ({@link ConnectedAppTest}) with mail-in on
 * against the mailbox of this JVM ({@link MailInServer}): {@code IMAP_HOST}, {@code IMAP_PORT}, {@code IMAP_USER},
 * {@code IMAP_PASSWORD}, the three folders, {@code IMAP_ALLOW_PLAINTEXT=true}, and
 * {@code MAIL_INBOUND_ADDRESS=support@popdesk.test}. The app's SMTP also goes to {@link MailInServer}, so a test reads
 * the notification it answers ({@link #mailIn()}{@code .sent()}); {@code MAIL_FROM} stays {@link MailServer#FROM}. One
 * context and one database of its own.
 *
 * <p>Because invitation mail goes to {@link MailInServer}, people are made with the steps here ({@link #customer},
 * {@link #agent}, {@link #invite}), not with {@code fixtures.customer}, which waits at {@link MailServer}. Everything
 * else of {@code fixtures} works.
 *
 * <p>Tests share the mailbox, the clock, and the rounds: each one makes its own people and companies, finds its mail by
 * the {@code Message-ID} or subject it made, and moves the clock only through {@link #rounds} and {@link #passTime}.
 */
@Import(MailInAppTest.MailInSettings.class)
@TestPropertySource(properties = {MailInAppTest.OWN_CONTEXT,
        "IMAP_HOST=" + MailInServer.HOST,
        "IMAP_USER=" + MailInServer.IMAP_USER,
        "IMAP_PASSWORD=" + MailInServer.IMAP_PASSWORD,
        "IMAP_FOLDER=popdesk-in",
        "IMAP_PROCESSED_FOLDER=popdesk-processed",
        "IMAP_QUARANTINE_FOLDER=popdesk-quarantine",
        "IMAP_ALLOW_PLAINTEXT=true",
        "MAIL_INBOUND_ADDRESS=" + MailInServer.ADDRESS})
public abstract class MailInAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "mailin.test.context=mailin";

    /** Notice A, in English (screens.md). */
    public static final String NOTICE_A_SUBJECT = "Your email did not become an issue";

    /** Notice A, in Korean. */
    public static final String NOTICE_A_SUBJECT_KO = "메일로 문의를 만들지 못했습니다";

    /**
     * {@code SMTP_HOST} and {@code SMTP_PORT} to {@link MailInServer}, and its {@code IMAP_PORT}. A registrar bean, so
     * it wins over the {@code @DynamicPropertySource} of {@link ConnectedAppTest}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class MailInSettings {

        @Bean
        static DynamicPropertyRegistrar mailInServers() {
            return registry -> {
                registry.add("SMTP_HOST", () -> MailInServer.HOST);
                registry.add("SMTP_PORT", () -> MailInServer.shared().smtpPort());
                registry.add("IMAP_PORT", () -> MailInServer.shared().imapPort());
            };
        }
    }

    /** The first admin, signed in on the console for this test. */
    protected Session admin;

    /** Rounds of this context, read as {@link #admin}. */
    protected Rounds rounds;

    @BeforeEach
    void setUpMailIn() {
        admin = fixtures.signInAdmin();
        rounds = new Rounds(admin, clock);
    }

    /** The mailbox and the app's outgoing mail. */
    protected MailInServer mailIn() {
        return MailInServer.shared();
    }

    // People

    /** A new company with a unique code, made by {@link #admin}. */
    protected Company company() {
        return fixtures.createCompany(admin);
    }

    /** Invites a new person with a unique email and reads the token from the invitation mail at {@link MailInServer}. */
    protected Invitation invite(Role role, String... companyCodes) {
        String prefix = role.name().toLowerCase(Locale.ROOT);
        String email = Unique.email(prefix);
        String name = Unique.text(prefix);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", name);
        body.put("role", role.name());
        if (companyCodes.length > 0) {
            body.put("companyCodes", List.of(companyCodes));
        }
        long accountId = admin.client().post("/api/console/invitations").json(body).expect(201).jsonLong("accountId");
        String link = Fixtures.linkIn(invitationTo(email).text());
        return new Invitation(accountId, email, name, role, List.of(companyCodes), link, Fixtures.tokenOf(link));
    }

    /** A new active customer of these companies, signed in on the portal. */
    protected Session customer(String... companyCodes) {
        return fixtures.accept(invite(Role.CUSTOMER, companyCodes));
    }

    /** A new active agent assigned to these companies, signed in on the console. */
    protected Session agent(String... assignedCompanyCodes) {
        Invitation invitation = invite(Role.AGENT);
        for (String code : assignedCompanyCodes) {
            fixtures.assignAgent(admin, code, invitation.accountId());
        }
        return fixtures.accept(invitation);
    }

    /** Sets the person's language to Korean ({@code PATCH .../me}) and gives the session back. */
    protected static Session korean(Session person) {
        person.client().patch(person.api("/me")).json(Map.of("locale", "ko")).expect(200);
        return person;
    }

    /**
     * Has an invitation mailed and waits for it, so every mail queued before is out by then. "No mail" is read after
     * this, not after a fixed wait.
     */
    protected void awaitALaterMail() {
        invite(Role.AGENT);
    }

    // Mail out

    /** The invitation mail to {@code email}; waits for it. */
    protected ReceivedMail invitationTo(String email) {
        return mailIn().awaitSent("invitation mail to " + email,
                m -> m.isTo(email) && m.text().contains("/invite#token="));
    }

    /** The mail to {@code email} whose subject holds {@code [key]}; waits for the first. */
    protected ReceivedMail awaitIssueMail(String email, String key) {
        return mailIn().awaitSent("mail about " + key + " to " + email,
                m -> m.isTo(email) && m.subject() != null && m.subject().contains("[" + key + "]"));
    }

    /**
     * An answer in public by {@code staff} on the issue ({@code STAFF_COMMENT}), and the notification mail it sends to
     * the reporter {@code customer}; waits for that mail.
     */
    protected ReceivedMail staffAnswer(Session staff, Session customer, String key) {
        fixtures.comment(staff, key, "Answer " + Unique.number());
        return awaitIssueMail(customer.email(), key);
    }

    /** The one {@code Message-ID} of a sent mail, as written. */
    protected static String messageIdOf(ReceivedMail mail) {
        List<String> values = mail.header("Message-ID");
        assertThat(values).as("Message-ID of the mail " + mail.subject()).hasSize(1);
        return values.get(0).trim();
    }

    /** The mail sent to the person, without invitations, oldest first. */
    protected List<ReceivedMail> mailTo(Session person) {
        return mailIn().sentTo(person.email()).stream().filter(m -> !m.text().contains("/invite#token=")).toList();
    }

    /** The first mail to the person with exactly this subject; waits for it. */
    protected ReceivedMail awaitMailTo(Session person, String subject) {
        return mailIn().awaitSent("mail \"" + subject + "\" to " + person.email(),
                m -> m.isTo(person.email()) && subject.equals(m.subject()));
    }

    /** Subject and text of a mail to the person, their name and email replaced by {@code {name}} and {@code {email}}. */
    protected static String withoutRecipient(ReceivedMail mail, Session person) {
        return (mail.subject() + "\n" + mail.text()).replace(person.email(), "{email}").replace(person.name(), "{name}");
    }

    /** The mail sent to the person with exactly this subject. */
    protected List<ReceivedMail> mailTo(Session person, String subject) {
        return mailTo(person).stream().filter(m -> subject.equals(m.subject())).toList();
    }

    // Mail in

    /**
     * Puts the mail in the incoming folder, unread, received ({@code INTERNALDATE}) at the app's time now, so the
     * mailbox cleanup (90 days by the app's clock) never takes a mail a test has just put in.
     */
    protected void send(Inbound mail) {
        send(mail.bytes());
    }

    /** Puts these raw bytes in the incoming folder as {@link #send(Inbound)} does. */
    protected void send(byte[] raw) {
        mailIn().deliver(Folder.INBOUND, raw, new Flags(), clock.instant());
    }

    /** A mail from the person (their registered address in {@code From}). */
    protected static Inbound mailFrom(Session person) {
        return Inbound.mail().from(person.email());
    }

    /** The folders that hold the mail now, by its {@code Message-ID}. */
    protected List<Folder> foldersOf(Inbound mail) {
        return mailIn().foldersOf(mail.messageId());
    }

    // Time

    /**
     * Moves the clock by {@code time} (more than a round), signs {@link #admin} in again (the session may have ended),
     * and waits for a good round after the move. Gives the time of that round.
     */
    protected Instant passTime(Duration time) {
        clock.advance(time);
        admin = fixtures.signInAgain(admin);
        rounds = new Rounds(admin, clock);
        return rounds.next();
    }

    // Received-mail records (DB: what HTTP does not show)

    /**
     * The received-mail record of a mail with one {@code From} address and a {@code Message-ID}, found by its key
     * (acceptance.md 2절: SHA-256 of the address in lower case, a line break, and the {@code Message-ID} without its
     * angle brackets); null while there is none. The key may be stored as hex or base64.
     */
    protected Map<String, Object> received(String fromAddress, String messageId) {
        List<Map<String, Object>> rows = receivedRows(fromAddress, messageId);
        assertThat(rows).as("received-mail records of " + messageId).hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Every received-mail record with the key of {@link #received}. */
    protected List<Map<String, Object>> receivedRows(String fromAddress, String messageId) {
        String id = messageId.strip().replaceAll("^<", "").replaceAll(">$", "").strip();
        byte[] key = sha256((fromAddress.toLowerCase(Locale.ROOT) + "\n" + id).getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(key);
        return db.rows("select * from mail_received where message_key in (?, ?, ?, ?)", hex,
                hex.toUpperCase(Locale.ROOT), Base64.getEncoder().encodeToString(key),
                Base64.getUrlEncoder().withoutPadding().encodeToString(key));
    }

    /** The {@code id} of the issue row with this key. */
    protected Object issueId(String key) {
        return db.row("select i.id from issue i join company c on c.id = i.company_id where c.code = ? and i.number = ?",
                key.substring(0, key.lastIndexOf('-')), Integer.parseInt(key.substring(key.lastIndexOf('-') + 1)))
                .get("id");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Issues, comments, attachments

    /** Every issue the person filed, in any company, as the admin's console list ({@code reporter=}), all pages. */
    protected List<JsonNode> issuesOf(Session person) {
        return issuesOf(person.accountId());
    }

    /** Every issue the account filed, as {@link #issuesOf(Session)}. */
    protected List<JsonNode> issuesOf(long accountId) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 50; page++) {
            ApiResponse r = admin.client().get("/api/console/issues").query("reporter", accountId)
                    .query("page", page).query("size", 100).expect(200);
            out.addAll(TalkFlow.items(r));
            if (page >= r.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("More than 50 pages of issues of account " + accountId);
    }

    /** The one issue the person filed; fails unless there is exactly one. Gives the console detail. */
    protected JsonNode onlyIssueOf(Session person) {
        List<JsonNode> issues = issuesOf(person);
        assertThat(issues).as("issues filed by " + person.email()).hasSize(1);
        return issue(issues.get(0).path("key").asString());
    }

    /** {@code GET /api/console/issues/{key}} as the admin. */
    protected JsonNode issue(String key) {
        return admin.client().get("/api/console/issues/" + key).expect(200).json();
    }

    /** Every comment on the issue, internal ones too, as the admin. */
    protected List<JsonNode> comments(String key) {
        return TalkFlow.comments(admin, key);
    }

    /** Every attachment of the issue, as the admin ({@code GET .../attachments}, one page of 100). */
    protected List<JsonNode> attachments(String key) {
        return TalkFlow.items(admin.client().get("/api/console/issues/" + key + "/attachments")
                .query("size", 100).expect(200));
    }
}
