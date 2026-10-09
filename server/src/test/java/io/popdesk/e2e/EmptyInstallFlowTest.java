package io.popdesk.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import io.popdesk.e2e.support.Actor;
import io.popdesk.e2e.support.Install;
import io.popdesk.e2e.support.Json;
import io.popdesk.e2e.support.Multipart;
import io.popdesk.e2e.support.Prometheus;
import io.popdesk.e2e.support.StandIns;
import io.popdesk.e2e.support.StandIns.Mail;
import io.popdesk.e2e.support.Steps;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The first slice's flow (acceptance.md section 1) on one installation from the release Compose
 * file, over HTTP only.
 *
 * <p>First the installation starts on empty volumes with only the first admin values and the
 * database password (secrets have no default, S0-AC-94), and the
 * admin signs in on the console host at the published port. Without SMTP, an agent joins with a
 * copied invitation link. Then the same installation (same volumes) is reconfigured the way an
 * operator would: behind Caddy with TLS, with SMTP (Mailpit) and a team webhook. The rest of the
 * flow runs through the proxy, and every link in a response or an email is opened as it is.
 *
 * <p>Each step is a test method in order. A step that fails makes every later step fail with an
 * assertion that names it.
 */
@Tag("deploy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmptyInstallFlowTest {

    private static final Duration HEALTHY = Duration.ofMinutes(5);

    private static final String ADMIN_EMAIL = "admin@e2e.test";
    private static final String ADMIN_PASSWORD = "Admin-e2e-pass-4417";
    private static final String DB_PASSWORD = "Db-e2e-pass-6081";
    private static final String LINK_AGENT_EMAIL = "linkagent@e2e.test";
    private static final String LINK_AGENT_NAME = "Lina Linkwell";
    private static final String LINK_AGENT_PASSWORD = "Linkagent-pass-2093";
    private static final String AGENT_EMAIL = "agent@e2e.test";
    private static final String AGENT_NAME = "Arno Agentson";
    private static final String AGENT_PASSWORD = "Agent-e2e-pass-7781";
    private static final String CUSTOMER_EMAIL = "customer@e2e.test";
    private static final String CUSTOMER_NAME = "Cora Customerby";
    private static final String CUSTOMER_PASSWORD = "Customer-e2e-pass-3302";
    private static final String SMTP_PASSWORD = "smtp-secret-5d1f9";
    private static final String HOOK_SECRET_PATH = "/hooks/e2e-hook-secret-8c2a";

    private static final String COMPANY = "E2EA";
    private static final String ISSUE_TITLE = "Export stops at 10 MB";
    private static final String ISSUE_CONTENT = "issue-content-marker-61b7: the export stops halfway.";
    private static final String AGENT_REPLY = "agent-reply-marker-93e0: please try the new build.";
    private static final String CUSTOMER_REPLY = "customer-reply-marker-4f2c: that fixed it.";
    private static final int FILE_SIZE = 10 * 1024 * 1024;
    private static final String FILE_NAME = "export-10mb.bin";

    private static final Pattern INVITE_LINK = Pattern.compile("https?://[^\\s\"'<>]+/invite#token=[A-Za-z0-9_.~%-]+");
    private static final Pattern ANY_LINK = Pattern.compile("https?://[^\\s\"'<>]+");

    private final Steps steps = new Steps();
    private final Install install = new Install("popdesk-e2e-flow");
    private StandIns standIns;

    private String app;
    private SSLContext proxyTrust;
    private int publicPort;
    private String consoleUrl;
    private String portalUrl;
    private Actor admin;
    private Actor agent;
    private Actor customer;
    private int agentId;
    private String issueKey;
    private String fileSha256;
    private Prometheus metricsBefore;
    private String firstAppLog = "";
    private final List<String> tokens = new ArrayList<>();

    @AfterAll
    void tearDown() {
        if (install.started()) {
            install.saveLog("app-first.log", firstAppLog);
            install.saveLog("app.log", install.logs("app"));
        }
        if (standIns != null) {
            install.saveLog("proxy.log", standIns.proxyLogs());
            standIns.close();
        }
        install.downWithVolumes();
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-94 an empty install with only the first admin values and the database password becomes healthy, and that admin signs in on the console host")
    void firstAdminOnly() throws Throwable {
        steps.run("firstAdminOnly", () -> {
            Install.assertReadyToStart();
            install.up(Install.settings("POPDESK_ADMIN_EMAIL", ADMIN_EMAIL, "POPDESK_ADMIN_PASSWORD", ADMIN_PASSWORD,
                    "POPDESK_DB_PASSWORD", DB_PASSWORD));
            app = install.containerId("app");
            install.awaitHealthy(app, HEALTHY);

            // Default host names (console.localhost) on the published port 8080.
            int port = install.publishedPort("app", 8080);
            admin = new Actor("admin", "http://console.localhost:" + port, null);
            admin.signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
            JsonNode me = admin.json(admin.get("/api/console/me"), 200);
            assertThat(me.get("email").asString()).isEqualTo(ADMIN_EMAIL);
            assertThat(me.get("role").asString()).isEqualTo("ADMIN");
        });
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-94 without SMTP, an agent joins with a copied invitation link that points at the console host and the public port")
    void copiedInvitationLinkWithoutSmtp() throws Throwable {
        steps.run("copiedInvitationLinkWithoutSmtp", () -> {
            JsonNode invited = admin.json(admin.postJson("/api/console/invitations",
                    Map.of("email", LINK_AGENT_EMAIL, "name", LINK_AGENT_NAME, "role", "AGENT")), 201);
            assertThat(invited.get("inviteLink").isNull()).as("without SMTP the invitation response holds the link").isFalse();
            long id = invited.get("accountId").asLong();

            JsonNode copied = admin.json(admin.post("/api/console/invitations/" + id + "/link"), 200);
            URI link = URI.create(copied.get("inviteLink").asString());
            // POPDESK_PUBLIC_SCHEME and POPDESK_PUBLIC_PORT are left at their defaults, http and 8080.
            assertInvitationLink(link, "http", "console.localhost", 8080);
            String token = token(link);
            assertThat(token).as("the copied link has a new token").isNotEqualTo(token(URI.create(invited.get("inviteLink").asString())));
            tokens.add(token(URI.create(invited.get("inviteLink").asString())));
            tokens.add(token);

            Actor linkAgent = new Actor("link agent", origin(link), null);
            assertHtmlPage(linkAgent, link);
            linkAgent.accept(token, LINK_AGENT_PASSWORD);
            JsonNode me = linkAgent.json(linkAgent.get("/api/console/me"), 200);
            assertThat(me.get("email").asString()).isEqualTo(LINK_AGENT_EMAIL);
            assertThat(me.get("role").asString()).isEqualTo("AGENT");
        });
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-94 the same install behind a TLS proxy, with SMTP and a webhook, is healthy again and keeps its accounts")
    void sameInstallBehindProxy() throws Throwable {
        steps.run("sameInstallBehindProxy", () -> {
            firstAppLog = install.logs("app");
            standIns = StandIns.start(install.network(app));
            publicPort = standIns.proxyPort();
            proxyTrust = standIns.proxyTrust();
            consoleUrl = "https://console.e2e.test:" + publicPort;
            portalUrl = "https://portal.e2e.test:" + publicPort;

            install.up(Install.settings(
                    "POPDESK_ADMIN_EMAIL", ADMIN_EMAIL,
                    "POPDESK_ADMIN_PASSWORD", ADMIN_PASSWORD,
                    "POPDESK_DB_PASSWORD", DB_PASSWORD,
                    "POPDESK_PORTAL_HOST", "portal.e2e.test",
                    "POPDESK_CONSOLE_HOST", "console.e2e.test",
                    "POPDESK_PUBLIC_SCHEME", "https",
                    "POPDESK_PUBLIC_PORT", String.valueOf(publicPort),
                    "SMTP_HOST", StandIns.MAIL_HOST,
                    "SMTP_PORT", "1025",
                    "SMTP_USER", "popdesk",
                    "SMTP_PASSWORD", SMTP_PASSWORD,
                    "SMTP_ALLOW_PLAINTEXT", "true",
                    "MAIL_FROM", "support@e2e.test",
                    "NOTIFY_WEBHOOK_URL", "http://" + StandIns.HOOK_HOST + ":8000" + HOOK_SECRET_PATH));
            String before = app;
            app = install.containerId("app");
            assertThat(app).as("Compose recreated the app container with the new settings").isNotEqualTo(before);
            install.awaitHealthy(app, HEALTHY);

            admin = new Actor("admin", consoleUrl, proxyTrust);
            admin.signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
            assertThat(admin.json(admin.get("/api/console/me"), 200).get("email").asString()).isEqualTo(ADMIN_EMAIL);
            Actor linkAgent = new Actor("link agent", consoleUrl, proxyTrust);
            linkAgent.signIn(LINK_AGENT_EMAIL, LINK_AGENT_PASSWORD);

            metricsBefore = standIns.metrics();
        });
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-94 the admin creates a customer company")
    void adminCreatesCompany() throws Throwable {
        steps.run("adminCreatesCompany", () -> {
            JsonNode company = admin.json(admin.postJson("/api/console/companies",
                    Map.of("code", COMPANY, "name", "E2E Alpha", "legalName", "E2E Alpha Ltd.")), 201);
            assertThat(company.get("code").asString()).isEqualTo(COMPANY);
            assertThat(company.get("status").asString()).isEqualTo("ACTIVE");
            JsonNode read = admin.json(admin.get("/api/console/companies/" + COMPANY), 200);
            assertThat(read.get("name").asString()).isEqualTo("E2E Alpha");
        });
    }

    @Test
    @Order(5)
    @DisplayName("S0-AC-94 the admin invites an agent, assigns them to the company, and invites a customer of the company")
    void adminInvitesAgentAndCustomer() throws Throwable {
        steps.run("adminInvitesAgentAndCustomer", () -> {
            JsonNode a = admin.json(admin.postJson("/api/console/invitations",
                    Map.of("email", AGENT_EMAIL, "name", AGENT_NAME, "role", "AGENT")), 201);
            assertThat(a.get("inviteLink").isNull()).as("with SMTP the link goes by email only").isTrue();
            agentId = a.get("accountId").asInt();
            admin.json(admin.put("/api/console/companies/" + COMPANY + "/agents/" + agentId), 200);

            JsonNode c = admin.json(admin.postJson("/api/console/invitations",
                    Map.of("email", CUSTOMER_EMAIL, "name", CUSTOMER_NAME, "role", "CUSTOMER", "companyCodes", List.of(COMPANY))), 201);
            assertThat(c.get("inviteLink").isNull()).as("with SMTP the link goes by email only").isTrue();

            JsonNode agents = admin.json(admin.get("/api/console/companies/" + COMPANY + "/agents"), 200);
            assertThat(agents.get("items").findValuesAsString("email")).contains(AGENT_EMAIL);
            JsonNode customers = admin.json(admin.get("/api/console/companies/" + COMPANY + "/customers"), 200);
            assertThat(customers.get("items").findValuesAsString("email")).contains(CUSTOMER_EMAIL);
        });
    }

    @Test
    @Order(6)
    @DisplayName("S0-AC-94 the agent and the customer accept from the invitation emails, whose links open on their app's public address")
    void invitedPeopleAcceptFromEmail() throws Throwable {
        steps.run("invitedPeopleAcceptFromEmail", () -> {
            agent = acceptFromEmail("agent", AGENT_EMAIL, AGENT_PASSWORD, "console.e2e.test", consoleUrl, "/api/console/me");
            customer = acceptFromEmail("customer", CUSTOMER_EMAIL, CUSTOMER_PASSWORD, "portal.e2e.test", portalUrl, "/api/portal/me");
            JsonNode companies = customer.json(customer.get("/api/portal/companies"), 200);
            assertThat(companies.get("items").findValuesAsString("code")).containsExactly(COMPANY);
        });
    }

    @Test
    @Order(7)
    @DisplayName("S0-AC-94 the customer files an issue on the portal with a 10 MB attachment, and gets the same bytes back")
    void customerFilesIssueWith10MbAttachment() throws Throwable {
        steps.run("customerFilesIssueWith10MbAttachment", () -> {
            byte[] file = new byte[FILE_SIZE];
            new Random(94).nextBytes(file);
            fileSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));

            Multipart body = new Multipart()
                    .json("issue", Map.of("companyCode", COMPANY, "title", ISSUE_TITLE, "content", ISSUE_CONTENT))
                    .file("files", FILE_NAME, "application/octet-stream", file);
            JsonNode issue = customer.json(customer.postMultipart("/api/portal/issues", body), 201);
            issueKey = issue.get("key").asString();
            assertThat(issueKey).startsWith(COMPANY + "-");
            assertThat(issue.get("status").asString()).isEqualTo("RECEIVED");

            JsonNode attachments = customer.json(customer.get("/api/portal/issues/" + issueKey + "/attachments"), 200);
            assertThat(attachments.get("items")).hasSize(1);
            JsonNode att = attachments.get("items").get(0);
            assertThat(att.get("fileName").asString()).isEqualTo(FILE_NAME);
            assertThat(att.get("size").asLong()).isEqualTo(FILE_SIZE);

            Actor.Download d = customer.download("/api/portal/issues/" + issueKey + "/attachments/" + att.get("id").asString());
            assertThat(d.status()).isEqualTo(200);
            assertThat(d.size()).isEqualTo(FILE_SIZE);
            assertThat(d.sha256()).isEqualTo(fileSha256);
        });
    }

    @Test
    @Order(8)
    @DisplayName("S0-AC-94 the team webhook receives the new issue with a console link and without customer details or content")
    void webhookReceivesNewIssue() throws Throwable {
        steps.run("webhookReceivesNewIssue", () -> {
            JsonNode hook = standIns.awaitHookRequests("webhook for " + issueKey, 1, r -> hookText(r).contains(issueKey)).getFirst();
            assertThat(hook.get("path").asString()).isEqualTo(HOOK_SECRET_PATH);
            assertThat(hook.get("contentType").asString()).startsWith("application/json");
            String text = hookText(hook);
            assertThat(text).contains(COMPANY).contains(ISSUE_TITLE).contains(consoleUrl + "/");
            assertThat(text).doesNotContain(CUSTOMER_EMAIL).doesNotContain(CUSTOMER_NAME).doesNotContain(ISSUE_CONTENT);
        });
    }

    @Test
    @Order(9)
    @DisplayName("S0-AC-94 the assigned agent opens the issue on the console, downloads the attachment, and answers publicly")
    void agentAnswers() throws Throwable {
        steps.run("agentAnswers", () -> {
            JsonNode list = agent.json(agent.get("/api/console/issues"), 200);
            assertThat(list.get("items").findValuesAsString("key")).contains(issueKey);
            JsonNode issue = agent.json(agent.get("/api/console/issues/" + issueKey), 200);
            assertThat(issue.get("content").asString()).isEqualTo(ISSUE_CONTENT);

            JsonNode att = agent.json(agent.get("/api/console/issues/" + issueKey + "/attachments"), 200).get("items").get(0);
            Actor.Download d = agent.download("/api/console/issues/" + issueKey + "/attachments/" + att.get("id").asString());
            assertThat(d.status()).isEqualTo(200);
            assertThat(d.sha256()).isEqualTo(fileSha256);

            JsonNode comment = agent.json(agent.postJson("/api/console/issues/" + issueKey + "/comments",
                    Map.of("content", AGENT_REPLY, "internal", false)), 201);
            assertThat(comment.get("internal").asBoolean()).isFalse();
        });
    }

    @Test
    @Order(10)
    @DisplayName("S0-AC-94 the customer gets one email about the answer, with a portal link that opens and nothing about the agent")
    void customerEmailArrives() throws Throwable {
        steps.run("customerEmailArrives", () -> {
            Mail mail = standIns.awaitMail("answer email to the customer", CUSTOMER_EMAIL,
                    m -> !INVITE_LINK.matcher(m.body()).find() && m.body().contains(issueKey));
            assertThat(mail.to()).containsExactly(CUSTOMER_EMAIL);
            assertThat(mail.subject()).contains(ISSUE_TITLE);
            assertThat(mail.body()).doesNotContain(AGENT_NAME).doesNotContain(AGENT_EMAIL);

            URI link = links(mail.body()).stream().filter(u -> u.toString().contains(issueKey)).findFirst()
                    .orElseThrow(() -> new AssertionError("the answer email has no link to " + issueKey + ":\n" + mail.body()));
            assertThat(link.getScheme()).isEqualTo("https");
            assertThat(link.getHost()).isEqualTo("portal.e2e.test");
            assertThat(link.getPort()).isEqualTo(publicPort);
            assertHtmlPage(customer, link);

            JsonNode comments = customer.json(customer.get("/api/portal/issues/" + issueKey + "/comments"), 200);
            JsonNode answer = null;
            for (JsonNode c : comments.get("items")) {
                if (AGENT_REPLY.equals(c.get("content").asString())) {
                    answer = c;
                }
            }
            assertThat(answer).as("the customer sees the answer").isNotNull();
            assertThat(answer.get("author").get("kind").asString()).isEqualTo("SUPPORT_TEAM");
            assertThat(answer.get("author").get("name").isNull()).isTrue();
        });
    }

    @Test
    @Order(11)
    @DisplayName("S0-AC-94 the customer replies on the portal, and the team webhook receives it")
    void customerRepliesAndWebhookReceivesIt() throws Throwable {
        steps.run("customerRepliesAndWebhookReceivesIt", () -> {
            customer.json(customer.postJson("/api/portal/issues/" + issueKey + "/comments", Map.of("content", CUSTOMER_REPLY)), 201);
            List<JsonNode> hooks = standIns.awaitHookRequests("second webhook for " + issueKey, 2,
                    r -> hookText(r).contains(issueKey));
            for (JsonNode h : hooks) {
                assertThat(hookText(h)).doesNotContain(CUSTOMER_REPLY).doesNotContain(CUSTOMER_EMAIL).doesNotContain(CUSTOMER_NAME);
            }
        });
    }

    @Test
    @Order(12)
    @DisplayName("S0-AC-94 the metrics on the management port went up: requests, sent emails and webhooks, attachment total")
    void metricsWentUp() throws Throwable {
        steps.run("metricsWentUp", () -> {
            Prometheus after = standIns.metrics();
            String requests = "http_server_requests_seconds_count";
            assertThat(after.sum(requests)).as(requests).isGreaterThan(metricsBefore.sum(requests));
            String sent = "popdesk_outbox_sent_total";
            // Two invitations and one answer by email; the new issue and the reply by webhook.
            assertThat(after.sum(sent, Map.of("channel", "mail"))).as("%s mail", sent)
                    .isGreaterThanOrEqualTo(metricsBefore.sum(sent, Map.of("channel", "mail")) + 3);
            assertThat(after.sum(sent, Map.of("channel", "webhook"))).as("%s webhook", sent)
                    .isGreaterThanOrEqualTo(metricsBefore.sum(sent, Map.of("channel", "webhook")) + 2);
            assertThat(after.has("popdesk_outbox_queued")).as("popdesk_outbox_queued is exposed").isTrue();
            String bytes = "popdesk_attachments_bytes";
            assertThat(after.sum(bytes)).as(bytes).isGreaterThanOrEqualTo(metricsBefore.sum(bytes) + FILE_SIZE);
        });
    }

    @Test
    @Order(13)
    @DisplayName("S0-AC-94 after the flow, the app and proxy logs hold no password, token, full email address, content, or webhook address")
    void logsHoldNoSecrets() throws Throwable {
        steps.run("logsHoldNoSecrets", () -> {
            String appLogs = firstAppLog + "\n" + install.logs("app");
            String proxyLogs = standIns.proxyLogs();
            assertThat(appLogs).as("app log").isNotBlank();
            assertThat(proxyLogs).as("Caddy access log (the `log` directive)").contains("http.log.access");
            for (String secret : List.of(ADMIN_PASSWORD, DB_PASSWORD, LINK_AGENT_PASSWORD, AGENT_PASSWORD, CUSTOMER_PASSWORD, SMTP_PASSWORD,
                    HOOK_SECRET_PATH, ISSUE_CONTENT, AGENT_REPLY, CUSTOMER_REPLY)) {
                assertThat(appLogs).as("app log holds a secret or content").doesNotContain(secret);
            }
            for (String token : tokens) {
                assertThat(appLogs).as("app log holds an invitation token").doesNotContain(token);
                assertThat(proxyLogs).as("proxy access log holds an invitation token").doesNotContain(token);
            }
            for (String email : List.of(ADMIN_EMAIL, LINK_AGENT_EMAIL, AGENT_EMAIL, CUSTOMER_EMAIL)) {
                for (String form : List.of(email, email.replace("@", "%40"), email.replace("@", "\\u0040"))) {
                    assertThat(appLogs).as("app log holds a full email address").doesNotContainIgnoringCase(form);
                }
            }
        });
    }

    // --- helpers ---

    private Actor acceptFromEmail(String who, String email, String password, String host, String appUrl, String mePath) {
        Mail mail = standIns.awaitMail("invitation email to " + who, email, m -> INVITE_LINK.matcher(m.body()).find());
        assertThat(mail.to()).containsExactly(email);
        Matcher m = INVITE_LINK.matcher(mail.body());
        assertThat(m.find()).isTrue();
        URI link = URI.create(m.group());
        assertInvitationLink(link, "https", host, publicPort);
        String token = token(link);
        tokens.add(token);

        Actor actor = new Actor(who, origin(link), proxyTrust);
        assertHtmlPage(actor, link);
        actor.accept(token, password);
        assertThat(actor.json(actor.get(mePath), 200).get("email").asString()).isEqualTo(email);

        Actor again = new Actor(who, appUrl, proxyTrust);
        again.signIn(email, password);
        assertThat(again.json(again.get(mePath), 200).get("email").asString()).isEqualTo(email);
        return again;
    }

    /** INVITE-2: {scheme}://{host}[:{port}]/invite#token={token}, the port left out when it is the default. */
    private static void assertInvitationLink(URI link, String scheme, String host, int port) {
        assertThat(link.getScheme()).as("scheme of %s", link).isEqualTo(scheme);
        assertThat(link.getHost()).as("host of %s", link).isEqualTo(host);
        assertThat(link.getPort()).as("port of %s", link).isEqualTo(port);
        assertThat(link.getPath()).as("path of %s", link).isEqualTo("/invite");
        assertThat(link.getRawQuery()).as("query of %s", link).isNull();
        assertThat(link.getRawFragment()).as("fragment of %s", link).startsWith("token=");
    }

    private static String token(URI link) {
        return link.getRawFragment().substring("token=".length());
    }

    private static String origin(URI link) {
        return link.getScheme() + "://" + link.getHost() + ":" + link.getPort();
    }

    /** Opens a link as a browser would (without the fragment) and expects the app's page. */
    private static void assertHtmlPage(Actor actor, URI link) {
        HttpResponse<String> page = actor.open(link);
        actor.expect(page, 200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).as("Content-Type of %s", link).startsWith("text/html");
    }

    /** The {@code text} of a webhook body, or "" when the body is not {@code {"text": ...}}; the step's asserts then fail. */
    private static String hookText(JsonNode request) {
        try {
            JsonNode text = Json.parse(request.get("body").asString()).get("text");
            return text == null ? "" : text.asString();
        } catch (tools.jackson.core.JacksonException e) {
            return "";
        }
    }

    private static List<URI> links(String text) {
        List<URI> found = new ArrayList<>();
        Matcher m = ANY_LINK.matcher(text);
        while (m.find()) {
            found.add(URI.create(m.group().replace("&amp;", "&")));
        }
        return found;
    }
}
