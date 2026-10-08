package io.popdesk.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Builds test data through the public API only, as a person in a browser would: sign in, make a
 * company, invite, accept, assign, file issues, comment, attach.
 *
 * <p>Every HTTP call here expects one status and fails with an {@link AssertionError} otherwise,
 * showing the request and the response body. Before the slice is built every endpoint answers 501,
 * so a test fails at its first fixture step, on an assertion, as the red check (C-6) wants.
 *
 * <p>Values are unique per JVM ({@link Unique}); a test that needs a value it can find later (in a
 * log, a mail, a webhook) passes its own.
 */
public final class Fixtures {

    /** Password for every invited account: 10 to 72 characters, not only whitespace, not an email. */
    public static final String PASSWORD = "fixture-Password-1";

    /** Where an account signs in. */
    public enum App {
        PORTAL("/api/portal"),
        CONSOLE("/api/console");

        private final String api;

        App(String api) {
            this.api = api;
        }

        /** The path prefix of this app's API. */
        public String api() {
            return api;
        }
    }

    /** Account roles, as the API writes them. */
    public enum Role {
        CUSTOMER,
        AGENT,
        ADMIN;

        public App app() {
            return this == CUSTOMER ? App.PORTAL : App.CONSOLE;
        }
    }

    /** A signed-in person: a client holding the session, and who it is. */
    public record Session(ApiClient client, App app, long accountId, String email, String name, String password,
            Role role) {

        /** {@code /api/portal} or {@code /api/console} plus {@code path}. */
        public String api(String path) {
            return app.api() + path;
        }
    }

    /** An invitation: the new account and the token from its link. */
    public record Invitation(long accountId, String email, String name, Role role, List<String> companyCodes,
            String link, String token) {}

    public record Company(String code, String name, String legalName, JsonNode json) {}

    public record Issue(String key, String companyCode, JsonNode json) {}

    public record Comment(long id, String issueKey, boolean internal, JsonNode json) {}

    public record Attachment(String id, String issueKey, byte[] content, JsonNode json) {}

    /** A file to upload. */
    public record File(String name, String contentType, byte[] content) {

        public static File text(String name, String content) {
            return new File(name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final Pattern TOKEN = Pattern.compile("#token=([^&\\s\"'<>]+)");

    private final AppTest app;

    Fixtures(AppTest app) {
        this.app = app;
    }

    // Sessions

    /** The first admin from {@code POPDESK_ADMIN_EMAIL}, signed in on the console. */
    public Session signInAdmin() {
        return signIn(App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
    }

    /** Signs in with a new client of {@code app}'s host, and reads who it is from {@code GET .../me}. */
    public Session signIn(App app, String email, String password) {
        ApiClient client = this.app.client(app);
        client.post("/api/public/sign-in").json(Map.of("email", email, "password", password)).expect(204);
        ApiResponse me = client.get(app.api() + "/me").expect(200);
        Role role = app == App.PORTAL ? Role.CUSTOMER : Role.valueOf(me.jsonText("role"));
        return new Session(client, app, me.jsonLong("id"), me.jsonText("email"), me.jsonText("name"), password, role);
    }

    /** The same person signed in again, in a new client (a second browser). */
    public Session signInAgain(Session session) {
        return signIn(session.app(), session.email(), session.password());
    }

    // Companies

    public Company createCompany(Session admin) {
        String code = Unique.companyCode();
        return createCompany(admin, code, "Company " + code, "Company " + code + " Ltd.");
    }

    public Company createCompany(Session admin, String code, String name, String legalName) {
        ApiResponse response = admin.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", name, "legalName", legalName))
                .expect(201);
        return new Company(response.jsonText("code"), name, legalName, response.json());
    }

    /** Assigns an agent (invited or active) to a company. */
    public void assignAgent(Session admin, String companyCode, long agentAccountId) {
        admin.client().put("/api/console/companies/" + companyCode + "/agents/" + agentAccountId).expect(200);
    }

    // Invitations

    /** Invites a new person with a unique email. Customers join {@code companyCodes}. */
    public Invitation invite(Session admin, Role role, String... companyCodes) {
        String prefix = role.name().toLowerCase(Locale.ROOT);
        return invite(admin, role, Unique.email(prefix), Unique.text(prefix), List.of(companyCodes));
    }

    /**
     * Invites a person and finds the token: from the link in the response when SMTP is off, from
     * the invitation mail when it is on.
     */
    public Invitation invite(Session admin, Role role, String email, String name, List<String> companyCodes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", name);
        body.put("role", role.name());
        if (!companyCodes.isEmpty()) {
            body.put("companyCodes", companyCodes);
        }
        ApiResponse response = admin.client().post("/api/console/invitations").json(body).expect(201);
        long accountId = response.jsonLong("accountId");
        String link = app.smtpOn()
                ? linkIn(app.mail().awaitMessageTo(email).text())
                : response.jsonText("inviteLink");
        return new Invitation(accountId, email, name, role, List.copyOf(companyCodes), link, tokenOf(link));
    }

    /**
     * Accepts an invitation on the host of the person's app; that signs them in (the accept call
     * answers 204 with a session). Returns the signed-in session.
     */
    public Session accept(Invitation invitation) {
        ApiClient client = app.client(invitation.role().app());
        client.post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", PASSWORD))
                .expect(204);
        return new Session(client, invitation.role().app(), invitation.accountId(), invitation.email(),
                invitation.name(), PASSWORD, invitation.role());
    }

    /** A signed-in customer of the given companies. */
    public Session customer(Session admin, String... companyCodes) {
        return accept(invite(admin, Role.CUSTOMER, companyCodes));
    }

    /** A signed-in agent, assigned to the given companies. */
    public Session agent(Session admin, String... assignedCompanyCodes) {
        Invitation invitation = invite(admin, Role.AGENT);
        for (String code : assignedCompanyCodes) {
            assignAgent(admin, code, invitation.accountId());
        }
        return accept(invitation);
    }

    /** Another signed-in admin. */
    public Session admin(Session admin) {
        return accept(invite(admin, Role.ADMIN));
    }

    /** The token in an invitation link ({@code .../invite#token=...}). Fails when there is none. */
    public static String tokenOf(String link) {
        if (link == null) {
            throw new AssertionError("No invitation link");
        }
        Matcher m = TOKEN.matcher(link);
        if (!m.find()) {
            throw new AssertionError("No #token= in invitation link " + link);
        }
        return m.group(1);
    }

    /** The first invitation link in a text (a mail body). */
    public static String linkIn(String text) {
        Matcher m = Pattern.compile("https?://[^\\s\"'<>]+/invite#token=[^&\\s\"'<>]+").matcher(text);
        if (!m.find()) {
            throw new AssertionError("No invitation link in: " + text);
        }
        return m.group();
    }

    // Issues, comments, attachments

    /** A new issue with a unique title, public, default priority. */
    public Issue fileIssue(Session customer, String companyCode) {
        return fileIssue(customer, NewIssue.in(companyCode));
    }

    /** Files an issue on the portal; with files, as one multipart request. */
    public Issue fileIssue(Session customer, NewIssue issue) {
        Map<String, Object> body = issue.body();
        ApiRequest request = customer.client().post("/api/portal/issues");
        if (issue.files.isEmpty()) {
            request.json(body);
        } else {
            request.jsonPart("issue", body);
            for (File f : issue.files) {
                request.file("files", f.name(), f.contentType(), f.content());
            }
        }
        ApiResponse response = request.expect(201);
        return new Issue(response.jsonText("key"), issue.companyCode, response.json());
    }

    /** A public comment. */
    public Comment comment(Session session, String issueKey, String content) {
        return comment(session, issueKey, content, false);
    }

    /** An internal comment (staff only). */
    public Comment internalComment(Session staff, String issueKey, String content) {
        return comment(staff, issueKey, content, true);
    }

    private Comment comment(Session session, String issueKey, String content, boolean internal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (internal) {
            body.put("internal", true);
        }
        ApiResponse response = session.client().post(session.api("/issues/" + issueKey + "/comments"))
                .json(body)
                .expect(201);
        return new Comment(response.jsonLong("id"), issueKey, internal, response.json());
    }

    /** Attaches a file to an issue. */
    public Attachment attach(Session session, String issueKey, File file) {
        return upload(session, issueKey, file, null);
    }

    /** Attaches a file to the caller's own comment. */
    public Attachment attachToComment(Session session, String issueKey, long commentId, File file) {
        return upload(session, issueKey, file, commentId);
    }

    private Attachment upload(Session session, String issueKey, File file, Long commentId) {
        ApiRequest request = session.client().post(session.api("/issues/" + issueKey + "/attachments"))
                .file("file", file.name(), file.contentType(), file.content());
        if (commentId != null) {
            request.part("commentId", commentId.toString());
        }
        ApiResponse response = request.expect(201);
        return new Attachment(response.jsonText("id"), issueKey, file.content(), response.json());
    }

    /** What to file. Starts with a unique title; change what the test cares about. */
    public static final class NewIssue {

        private final String companyCode;
        private String title = Unique.text("Issue");
        private String content = "Steps to reproduce.";
        private String priority;
        private Boolean isPrivate;
        private final List<File> files = new ArrayList<>();

        private NewIssue(String companyCode) {
            this.companyCode = companyCode;
        }

        public static NewIssue in(String companyCode) {
            return new NewIssue(companyCode);
        }

        public NewIssue title(String title) {
            this.title = title;
            return this;
        }

        public NewIssue content(String content) {
            this.content = content;
            return this;
        }

        /** {@code LOW}, {@code NORMAL}, {@code HIGH}, or {@code URGENT}; left out when not set. */
        public NewIssue priority(String priority) {
            this.priority = priority;
            return this;
        }

        public NewIssue privateIssue() {
            this.isPrivate = true;
            return this;
        }

        public NewIssue file(File file) {
            files.add(file);
            return this;
        }

        public String title() {
            return title;
        }

        Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("companyCode", companyCode);
            body.put("title", title);
            body.put("content", content);
            if (priority != null) {
                body.put("priority", priority);
            }
            if (isPrivate != null) {
                body.put("private", isPrivate);
            }
            return body;
        }
    }
}
