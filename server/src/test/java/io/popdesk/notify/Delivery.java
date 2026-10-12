package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.support.WebhookReceiver.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of slice 4b (delivery operations) share: three setups of the app, an SMTP port a test
 * can script, failed queue rows, and the calls of the two new endpoints. Public, because tests of other
 * packages use it.
 *
 * <p>The setups. Each is one test context; extend one of them and add no {@code @TestPropertySource}.
 *
 * <ul>
 *   <li>{@link PlainApp}: "connected", not a sandbox. {@code POPDESK_SANDBOX=false} is written out and
 *       {@code POPDESK_SANDBOX_MAIL_TO} holds an address the app must not read.
 *   <li>{@link SandboxWithAddress}: {@code POPDESK_SANDBOX=true}, all mail goes to {@link #SANDBOX_TO}.
 *   <li>{@link SandboxNoAddress}: {@code POPDESK_SANDBOX=true} and no address: no mail at all.
 * </ul>
 *
 * In all three SMTP goes through {@link FakeSmtp} (then {@link SmtpGate}, then GreenMail), the webhook
 * goes to {@code WebhookReceiver} at {@code /hook}, and the outside copy of the audit log to the same
 * receiver at {@link #AUDIT_COPY_PATH} with {@link #AUDIT_COPY_TOKEN}. So a request the receiver got can
 * be an audit copy: read webhooks with {@link #webhooks} and copies with {@link #auditCopies}, not with
 * {@code containing} alone.
 */
public final class Delivery {

    /** Where a sandbox sends all mail. No other address in the tests starts with {@code z}. */
    public static final String SANDBOX_TO = "zsandbox-inbox@ops.example.test";

    /** {@code POPDESK_SANDBOX_MAIL_TO} of {@link PlainApp}, which is no sandbox and must not read the key. */
    public static final String UNREAD_SANDBOX_TO = "zunread-key@ops.example.test";

    public static final String AUDIT_COPY_PATH = "/delivery-audit-copy";
    public static final String AUDIT_COPY_TOKEN = "delivery-Copy-Token-1";

    public static final String STATUS = "/api/console/system/status";
    public static final String FAILED = "/api/console/system/failed-deliveries";
    public static final String TEST_MAIL = "/api/console/system/test-mail";

    /** One second past the 24 hours after which a failing row is given up (D-39). */
    public static final Duration PAST_GIVING_UP = Duration.ofHours(24).plusSeconds(1);

    /** Past the 7 days a failed row is kept, and past the hour the mail limit looks back. */
    public static final Duration PAST_CLEANUP = Duration.ofDays(8);

    /** The mail limit of one address (MAIL-6): this many in {@link #MAIL_LIMIT_WINDOW}. */
    public static final int MAIL_LIMIT = 50;

    public static final Duration MAIL_LIMIT_WINDOW = Duration.ofMinutes(60);

    /** The three channels as the status names them, by the name the failed list gives them. */
    public static final Map<String, String> CHANNELS = Map.of("MAIL", "mail", "WEBHOOK", "webhook",
            "AUDIT_FORWARD", "auditForward");

    private Delivery() {}

    // Setups

    /** Points SMTP at {@link FakeSmtp} and the outside copy at the webhook receiver. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        static DynamicPropertyRegistrar deliverySettings() {
            return registry -> {
                registry.add("SMTP_PORT", () -> FakeSmtp.shared().port());
                registry.add("AUDIT_FORWARD_URL", Delivery::auditCopyUrl);
            };
        }
    }

    static String auditCopyUrl() {
        return "http://127.0.0.1:" + WebhookReceiver.shared().port() + AUDIT_COPY_PATH;
    }

    /**
     * What the three setups share. Before each test SMTP passes mail through and the receiver answers
     * 204; after each test too. The people here are made without {@code Fixtures.invite}, which waits
     * for the invitation at the invited address and so never ends in a sandbox.
     */
    @Import(Wiring.class)
    @TestPropertySource(properties = "AUDIT_FORWARD_TOKEN=" + AUDIT_COPY_TOKEN)
    public abstract static class App extends ConnectedAppTest {

        @Autowired
        private Environment environment;

        /** The SMTP port of this setup. */
        protected FakeSmtp smtp() {
            return FakeSmtp.shared();
        }

        @BeforeEach
        void openSmtp() {
            assertThat(environment.getProperty("SMTP_PORT")).as("SMTP_PORT of this context")
                    .isEqualTo(String.valueOf(smtp().port()));
            smtp().reset();
            SmtpGate.shared().reset();
        }

        @AfterEach
        void reopenOutsideSystems() {
            smtp().pass();
            SmtpGate.shared().pass();
            webhook().respondWith(204);
        }

        /** The first admin, signed in again: moving the clock a day ends every session. */
        protected Session admin() {
            return fixtures.signInAdmin();
        }

        // People

        /** An admin, a company, a customer of it, and an agent assigned to it. */
        public record Cast(Session admin, Company company, Session customer, Session agent) {

            public String code() {
                return company.code();
            }
        }

        protected Cast cast() {
            Session admin = admin();
            Company company = fixtures.createCompany(admin);
            return new Cast(admin, company, person(admin, Role.CUSTOMER, "Customer", company.code()),
                    person(admin, Role.AGENT, "Staffer", company.code()));
        }

        /**
         * A signed-in person with a name and an email of their own ({@code <prefix>-12@example.test}).
         * A customer joins the companies; an agent is assigned to them.
         */
        protected Session person(Session admin, Role role, String prefix, String... companyCodes) {
            List<String> joins = role == Role.CUSTOMER ? List.of(companyCodes) : List.of();
            Invitation invitation = invite(admin, role, Unique.email(prefix.toLowerCase(Locale.ROOT)),
                    Unique.text(prefix), joins);
            if (role != Role.CUSTOMER) {
                for (String code : companyCodes) {
                    fixtures.assignAgent(admin, code, invitation.accountId());
                }
            }
            return fixtures.accept(invitation);
        }

        /**
         * Invites and finds the link wherever this setup puts it: in the response when mail is off, in
         * the mail to the invited address, or in the mail a sandbox sent to {@link #SANDBOX_TO} whose
         * first line names the account.
         */
        protected Invitation invite(Session admin, Role role, String email, String name, List<String> companyCodes) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", email);
            body.put("name", name);
            body.put("role", role.name());
            if (!companyCodes.isEmpty()) {
                body.put("companyCodes", companyCodes);
            }
            ApiResponse response = admin.client().post("/api/console/invitations").json(body).expect(201);
            long accountId = response.jsonLong("accountId");
            JsonNode inResponse = response.json().path("inviteLink");
            String link = inResponse.isString() ? inResponse.stringValue()
                    : Fixtures.linkIn(awaitInvitationMail(email, accountId).text());
            return new Invitation(accountId, email, name, role, List.copyOf(companyCodes), link,
                    Fixtures.tokenOf(link));
        }

        private ReceivedMail awaitInvitationMail(String email, long accountId) {
            return Wait.until("the invitation mail of account " + accountId + ", to " + email + " or to the sandbox "
                    + "address", () -> mail().messages().stream()
                    .filter(m -> m.text().contains("/invite#token="))
                    .filter(m -> m.isTo(email) || (m.isTo(SANDBOX_TO) && namesAccount(firstLine(m), accountId)))
                    .findFirst().orElse(null));
        }

        /**
         * Has an invitation mailed and waits for it: every mail queued before is out by then, so "no
         * mail" is read after this and not after a fixed wait.
         */
        protected void awaitALaterMail(Session admin) {
            invite(admin, Role.AGENT, Unique.email("later"), Unique.text("Later"), List.of());
        }

        /** Staff answer the issue {@code n} times with public comments: {@code n} mails to its reporter. */
        protected void answer(Session staff, String issueKey, int n) {
            for (int i = 0; i < n; i++) {
                fixtures.comment(staff, issueKey, "Answer " + Unique.number());
            }
        }

        // Time

        /** Moves the app clock a second on and gives the new time as the database keeps it. */
        protected Instant tick() {
            return clock.advance(Duration.ofSeconds(1)).truncatedTo(ChronoUnit.MICROS);
        }

        /**
         * Moves the clock eight days on and waits until no channel has a failed row: failed rows of
         * earlier tests are cleaned up, no alert is open, and no address has mail counted against it.
         * Gives the first admin, signed in after the move.
         */
        protected Session startClean() {
            clock.advance(PAST_CLEANUP);
            Session admin = admin();
            DeliverySteps.awaitNoFailedRow(admin);
            return admin;
        }

        // Failed rows

        /**
         * Makes a mail row fail for good: refuses all mail, runs {@code cause}, waits for the first
         * refused attempt at {@code sentTo} (the recipient's address, in a sandbox {@link #SANDBOX_TO}),
         * and moves the clock past the 24 hours. Gives the row as the failed list shows it. Mail passes
         * again afterwards; every session from before has ended.
         */
        protected JsonNode failMail(long recipientAccountId, String sentTo, Runnable cause) {
            SmtpGate gate = SmtpGate.shared();
            gate.hold();
            cause.run();
            gate.awaitHeldTo(sentTo);
            clock.advance(PAST_GIVING_UP);
            Session admin = admin();
            JsonNode row = awaitFailedRow(admin, "a failed mail row of account " + recipientAccountId,
                    n -> "MAIL".equals(n.path("channel").asString(null))
                            && n.path("recipientAccountId").asLong(-1) == recipientAccountId);
            gate.pass();
            return row;
        }

        /**
         * Makes a webhook row fail for good: the receiver answers 500 to webhooks only, {@code cause}
         * does what queues one and gives a text its body holds (an issue key), and the clock moves past
         * the 24 hours. The receiver answers 204 again afterwards.
         */
        protected JsonNode failWebhook(Supplier<String> cause) {
            Instant at = tick();
            webhook().respond(r -> Reply.status(isAuditCopy(r) ? 204 : 500));
            String text = cause.get();
            webhook().await("a first webhook attempt holding " + text, r -> !isAuditCopy(r) && r.text().contains(text));
            clock.advance(PAST_GIVING_UP);
            Session admin = admin();
            JsonNode row = awaitFailedRow(admin, "a failed webhook row created at " + at,
                    n -> "WEBHOOK".equals(n.path("channel").asString(null)) && at.equals(createdAt(n)));
            webhook().respondWith(204);
            return row;
        }

        /**
         * Makes the outside copies of what {@code cause} writes to the audit log fail for good, and
         * gives their rows (one per audit entry written in that second). Only in {@link PlainApp}; a
         * sandbox queues no copy.
         */
        protected List<JsonNode> failAuditCopies(Runnable cause) {
            Instant at = tick();
            int before = auditCopies(webhook(), "").size();
            webhook().respond(r -> Reply.status(isAuditCopy(r) ? 500 : 204));
            cause.run();
            Wait.until("a first attempt of an audit copy", () -> auditCopies(webhook(), "").size() > before ? at : null);
            clock.advance(PAST_GIVING_UP);
            Session admin = admin();
            Predicate<JsonNode> mine = n -> "AUDIT_FORWARD".equals(n.path("channel").asString(null))
                    && at.equals(createdAt(n));
            awaitFailedRow(admin, "a failed audit copy row created at " + at, mine);
            webhook().respondWith(204);
            return failedRows(admin).stream().filter(mine).toList();
        }

        // Metrics

        /**
         * The sum of the samples named {@code name} in {@code /actuator/prometheus}, or -1 when there is
         * none.
         */
        protected double metric(String name) {
            String port = environment.getProperty("local.management.port");
            if (port == null) {
                throw new AssertionError("The app has no management port (local.management.port is not set)");
            }
            String text = new ApiClient("localhost", Integer.parseInt(port)).get("/actuator/prometheus")
                    .header("Accept", "text/plain").send().expectStatus(200).text();
            List<String> samples = text.lines().filter(l -> l.startsWith(name + " ") || l.startsWith(name + "{"))
                    .toList();
            if (samples.isEmpty()) {
                return -1;
            }
            return samples.stream().mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
        }
    }

    /**
     * Not a sandbox, with the two sandbox keys written out as an operator who turned it off again would
     * leave them. For failed rows, resending, the test mail, the mail limit, and the alerts.
     */
    @TestPropertySource(properties = {"POPDESK_SANDBOX=false", "POPDESK_SANDBOX_MAIL_TO=" + UNREAD_SANDBOX_TO})
    public abstract static class PlainApp extends App {}

    /**
     * A sandbox whose mail all goes to {@link #SANDBOX_TO}. That one address gets at most 50 mails an
     * hour (S4B-AC-13), and the clock stands still, so the clock moves 61 minutes on before each test:
     * a test starts with none counted and has 50 to use, invitations included.
     */
    @TestPropertySource(properties = {"POPDESK_SANDBOX=true", "POPDESK_SANDBOX_MAIL_TO=" + SANDBOX_TO})
    public abstract static class SandboxWithAddress extends App {

        @BeforeEach
        void newHour() {
            clock.advance(MAIL_LIMIT_WINDOW.plusMinutes(1));
        }
    }

    /** A sandbox without an address: SMTP is set up, and still no mail goes anywhere. */
    @TestPropertySource(properties = "POPDESK_SANDBOX=true")
    public abstract static class SandboxNoAddress extends App {}

    // The two new endpoints

    /** The path of {@code POST .../failed-deliveries/{id}/resend}. {@code id} may be any text. */
    public static String resendPath(Object id) {
        return FAILED + "/" + id + "/resend";
    }

    /** {@code POST .../failed-deliveries/{id}/resend}, not sent yet. {@code id} may be any text. */
    public static ApiRequest resendRequest(Session admin, Object id) {
        return admin.client().post(resendPath(id));
    }

    /** Sends a failed row again; expects 204. */
    public static void resend(Session admin, long id) {
        resendRequest(admin, id).expect(204);
    }

    /** {@code POST .../test-mail}, not sent yet. */
    public static ApiRequest testMailRequest(Session admin) {
        return admin.client().post(TEST_MAIL);
    }

    /** Sends a test mail; expects 200 and gives the body ({@code ok}, {@code errorKind}, {@code serverReply}). */
    public static JsonNode testMail(Session admin) {
        return testMailRequest(admin).expect(200).json();
    }

    // Status and the failed list

    public static JsonNode status(Session admin) {
        return admin.client().get(STATUS).expect(200).json();
    }

    /** Every row of the failed list, all pages, newest first. */
    public static List<JsonNode> failedRows(Session admin) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiResponse response = admin.client().get(FAILED).query("page", page).query("size", 100).expect(200);
            JsonNode items = response.json().path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The failed list has more than 1000 pages");
    }

    /** The failed mail rows of one receiving account. */
    public static List<JsonNode> failedMailRows(Session admin, long recipientAccountId) {
        return failedRows(admin).stream().filter(n -> "MAIL".equals(n.path("channel").asString(null))
                && n.path("recipientAccountId").asLong(-1) == recipientAccountId).toList();
    }

    public static JsonNode awaitFailedRow(Session admin, String what, Predicate<JsonNode> filter) {
        return Wait.until(what + " in the failed list",
                () -> failedRows(admin).stream().filter(filter).findFirst().orElse(null));
    }

    /** Whether the failed list still has the row. */
    public static boolean isFailed(Session admin, long id) {
        return failedRows(admin).stream().anyMatch(n -> n.path("id").asLong(-1) == id);
    }

    /** {@code createdAt} of a failed row, or null when it is no timestamp. */
    public static Instant createdAt(JsonNode row) {
        JsonNode at = row.path("createdAt");
        return at.isString() ? OffsetDateTime.parse(at.stringValue()).toInstant() : null;
    }

    // The receiver

    public static boolean isAuditCopy(Received request) {
        return request.path().startsWith(AUDIT_COPY_PATH);
    }

    /** Webhook requests (not audit copies) whose body contains {@code text}. */
    public static List<Received> webhooks(WebhookReceiver receiver, String text) {
        return receiver.requests(r -> !isAuditCopy(r) && r.text().contains(text));
    }

    /** Audit copies whose body contains {@code text}. */
    public static List<Received> auditCopies(WebhookReceiver receiver, String text) {
        return receiver.requests(r -> isAuditCopy(r) && r.text().contains(text));
    }

    // Sandbox mail

    /** The first line of the mail's text that is not empty. */
    public static String firstLine(ReceivedMail mail) {
        return mail.text().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
    }

    /** Whether the line holds the account ID as a number of its own (12 is not in 312 or 120). */
    public static boolean namesAccount(String line, long accountId) {
        return Pattern.compile("(?<![0-9])" + accountId + "(?![0-9])").matcher(line).find();
    }

    /** Mail at {@link #SANDBOX_TO} whose first line names the account: what a sandbox redirected from them. */
    public static List<ReceivedMail> redirected(MailServer mail, long accountId) {
        return mail.messagesTo(SANDBOX_TO).stream().filter(m -> namesAccount(firstLine(m), accountId)).toList();
    }

    public static ReceivedMail awaitRedirected(MailServer mail, long accountId, Predicate<ReceivedMail> filter) {
        return Wait.until("a mail at the sandbox address whose first line names account " + accountId,
                () -> redirected(mail, accountId).stream().filter(filter).findFirst().orElse(null));
    }

    // Addresses and secrets in text

    /**
     * The forms an address or a secret could take in a log or a response: as it is, with {@code %40},
     * as Base64, and for an address the part between its first character and the {@code @}, which a
     * masked address never shows (left out when it is shorter than 6 characters and could be anything).
     */
    public static List<String> forms(String value) {
        List<String> out = new ArrayList<>(List.of(value, value.replace("@", "%40"),
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        int at = value.indexOf('@');
        if (at >= 7) {
            out.add(value.substring(1, at));
        }
        return out;
    }

    /** The forms of {@code value} that {@code text} holds, ignoring case; empty when it holds none. */
    public static List<String> foundIn(String text, String value) {
        String lower = text.toLowerCase(Locale.ROOT);
        return forms(value).stream().filter(f -> lower.contains(f.toLowerCase(Locale.ROOT))).toList();
    }

    /**
     * Lines that hold an address masked after its first character {@code first}: {@code z***}. The
     * mask character is taken to be {@code *} (or {@code •}, {@code …}), as {@code NoSmtpMailLogTest}
     * takes it.
     */
    public static List<String> maskedLines(List<String> lines, char first) {
        Pattern masked = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(String.valueOf(first)) + "[*•…]+");
        return lines.stream().filter(l -> masked.matcher(l).find()).toList();
    }

    // SMTP

    /**
     * The SMTP port of the three setups: a plain port in front of {@link SmtpGate} (and so GreenMail)
     * that answers with fixed texts when a test says so, for refusals GreenMail cannot make.
     *
     * <ul>
     *   <li>{@link #pass()}, the default: each connection goes through unchanged.
     *   <li>{@link #script}: the port answers SMTP itself. For each command line the script gives the
     *       reply, or null for the usual answer of a server that takes everything. A message it took is
     *       kept in {@link #taken()} with the reply it got, and goes nowhere else.
     *   <li>{@link #drop()}: each connection is closed at once, as a server that went away.
     * </ul>
     *
     * The state is read when a connection opens. No STARTTLS; {@code AUTH PLAIN} only, so the client's
     * name and password come in one command line. One port for the whole JVM ({@link #shared()}); mail
     * of another test context can come in between, so find yours by its recipient.
     *
     * <p>To make sending fail and try again later, {@link #refuseMessage} with a 4xx reply does it.
     */
    public static final class FakeSmtp {

        /** What the script is asked after the message's last line, in place of a command. */
        public static final String END_OF_DATA = "<end of data>";

        /** A message the port took while scripted, with the reply to its last line. */
        public record Taken(String from, List<String> recipients, String raw, String reply) {

            public boolean isTo(String address) {
                return recipients.stream().anyMatch(r -> r.equalsIgnoreCase(address));
            }
        }

        private static FakeSmtp shared;

        private final ServerSocket server;
        private final List<Taken> taken = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private volatile Function<String, String> script;
        private volatile boolean dropping;

        private FakeSmtp() {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            daemon("fake-smtp-accept", this::acceptLoop).start();
        }

        public static synchronized FakeSmtp shared() {
            if (shared == null) {
                shared = new FakeSmtp();
            }
            return shared;
        }

        public int port() {
            return server.getLocalPort();
        }

        /** From now on, pass new connections through. */
        public void pass() {
            script = null;
            dropping = false;
        }

        /**
         * From now on, answer new connections by {@code script}: command line in, reply out (several
         * lines as {@code "550-first\r\n550 second"}), or null for the usual answer.
         */
        public void script(Function<String, String> script) {
            this.script = script;
            dropping = false;
        }

        /** Answers {@code reply} to every command that starts with {@code command}, ignoring case. */
        public void refuse(String command, String reply) {
            String prefix = command.toUpperCase(Locale.ROOT);
            script(line -> line.toUpperCase(Locale.ROOT).startsWith(prefix) ? reply : null);
        }

        /** Refuses the sign-in, for example with {@code "535 5.7.8 No such user"}. */
        public void refuseLogin(String reply) {
            refuse("AUTH", reply);
        }

        /** Refuses every recipient, for example with {@code "550 5.1.1 No such mailbox"}. */
        public void refuseRecipient(String reply) {
            refuse("RCPT TO:", reply);
        }

        /** Takes the whole message and refuses it at its end, for example with {@code "451 4.3.0 Later"}. */
        public void refuseMessage(String reply) {
            refuse(END_OF_DATA, reply);
        }

        /** From now on, close new connections at once. */
        public void drop() {
            dropping = true;
        }

        /** Messages taken while scripted, oldest first. */
        public List<Taken> taken() {
            return List.copyOf(taken);
        }

        public Taken awaitTakenTo(String address) {
            return Wait.until("a mail to " + address + " at the scripted SMTP port",
                    () -> taken.stream().filter(t -> t.isTo(address)).findFirst().orElse(null));
        }

        /** Connections opened since the last {@link #reset()}, in any state. */
        public int connections() {
            return connections.get();
        }

        /** Passes mail through again and forgets what it took. */
        public void reset() {
            pass();
            taken.clear();
            connections.set(0);
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    connections.incrementAndGet();
                    Function<String, String> answers = script;
                    if (dropping) {
                        client.close();
                    } else if (answers == null) {
                        daemon("fake-smtp-pass", () -> passSession(client)).start();
                    } else {
                        daemon("fake-smtp-script", () -> scriptSession(client, answers)).start();
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void passSession(Socket client) {
            try (client; Socket backend = new Socket(InetAddress.getLoopbackAddress(), SmtpGate.shared().port())) {
                Thread back = daemon("fake-smtp-pipe", () -> copy(backend, client));
                back.start();
                copy(client, backend);
                back.join(30_000);
            } catch (IOException e) {
                // One side went away; the app sees that on its side.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void scriptSession(Socket socket, Function<String, String> answers) {
            try (socket) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                reply(out, "220 popdesk-fake ESMTP");
                String from = null;
                List<String> recipients = new ArrayList<>();
                while (true) {
                    String line = readLine(in);
                    if (line == null) {
                        return;
                    }
                    String upper = line.toUpperCase(Locale.ROOT);
                    String scripted = answers.apply(line);
                    if (scripted != null) {
                        reply(out, scripted);
                        if (upper.equals("DATA") && scripted.startsWith("354")) {
                            String raw = readData(in);
                            String end = answer(answers, END_OF_DATA, "250 2.0.0 OK");
                            taken.add(new Taken(from, List.copyOf(recipients), raw, end));
                            reply(out, end);
                        } else if (upper.equals("QUIT")) {
                            return;
                        }
                    } else if (upper.startsWith("EHLO")) {
                        reply(out, "250-popdesk-fake\r\n250-AUTH PLAIN\r\n250 8BITMIME");
                    } else if (upper.startsWith("HELO")) {
                        reply(out, "250 popdesk-fake");
                    } else if (upper.equals("AUTH PLAIN")) {
                        reply(out, "334 ");
                        readLine(in);
                        reply(out, "235 2.7.0 Accepted");
                    } else if (upper.startsWith("AUTH ")) {
                        reply(out, "235 2.7.0 Accepted");
                    } else if (upper.startsWith("MAIL FROM:")) {
                        from = address(line.substring(10));
                        recipients.clear();
                        reply(out, "250 2.1.0 OK");
                    } else if (upper.startsWith("RCPT TO:")) {
                        recipients.add(address(line.substring(8)));
                        reply(out, "250 2.1.5 OK");
                    } else if (upper.equals("DATA")) {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        String raw = readData(in);
                        String end = answer(answers, END_OF_DATA, "250 2.0.0 OK");
                        taken.add(new Taken(from, List.copyOf(recipients), raw, end));
                        reply(out, end);
                    } else if (upper.equals("RSET") || upper.equals("NOOP")) {
                        reply(out, "250 2.0.0 OK");
                    } else if (upper.equals("QUIT")) {
                        reply(out, "221 2.0.0 Bye");
                        return;
                    } else {
                        reply(out, "502 5.5.1 Not implemented");
                    }
                }
            } catch (IOException e) {
                // The app went away.
            }
        }

        private static String answer(Function<String, String> answers, String line, String usual) {
            String scripted = answers.apply(line);
            return scripted == null ? usual : scripted;
        }

        private static String address(String value) {
            String v = value.strip();
            int lt = v.indexOf('<');
            int gt = v.indexOf('>');
            return lt >= 0 && gt > lt ? v.substring(lt + 1, gt) : v.split("\\s")[0];
        }

        /** The message up to the line with a single dot, with dot-stuffing undone. Bytes kept as Latin-1. */
        private static String readData(InputStream in) throws IOException {
            StringBuilder data = new StringBuilder();
            while (true) {
                String line = readLine(in);
                if (line == null || line.equals(".")) {
                    return data.toString();
                }
                data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
            }
        }

        private static void copy(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException e) {
                // One side closed.
            } finally {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                    // Already closed.
                }
            }
        }

        private static void reply(OutputStream out, String text) throws IOException {
            out.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** One CRLF line, bytes read as Latin-1. Null at the end of the stream. */
        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    String text = line.toString(StandardCharsets.ISO_8859_1);
                    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                }
                line.write(b);
            }
            return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
        }

        private static Thread daemon(String name, Runnable task) {
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            return t;
        }
    }
}
