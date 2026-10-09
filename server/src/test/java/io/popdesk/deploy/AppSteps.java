package io.popdesk.deploy;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Test data in an app that runs in a container, through its public API only, as {@code Fixtures}
 * does for the app in the test JVM. Every step expects one status and fails with an
 * {@link AssertionError} otherwise; before the slice is built the first step fails.
 */
final class AppSteps {

    /** A signed-in person: a client of their app's host and who they are. */
    record Person(ApiClient client, String api, long accountId, String email, String password) {}

    private final Stack stack;

    AppSteps(Stack stack) {
        this.stack = stack;
    }

    Person signInAdmin() {
        return signIn(Stack.ADMIN_EMAIL, Stack.ADMIN_PASSWORD);
    }

    /** Signs in on the console. */
    Person signIn(String email, String password) {
        ApiClient client = stack.console();
        client.post("/api/public/sign-in").json(Map.of("email", email, "password", password)).expect(204);
        ApiResponse me = client.get("/api/console/me").expect(200);
        return new Person(client, "/api/console", me.jsonLong("id"), email, password);
    }

    /** A new company; returns its code. */
    String createCompany(Person admin) {
        String code = Unique.companyCode();
        admin.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", "Company " + code, "legalName", "Company " + code + " Ltd."))
                .expect(201);
        return code;
    }

    /** Invites a person and returns the invitation response (with {@code inviteLink} when SMTP is off). */
    ApiResponse invite(Person admin, String role, String email, List<String> companyCodes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", Unique.text(role.toLowerCase()));
        body.put("role", role);
        if (!companyCodes.isEmpty()) {
            body.put("companyCodes", companyCodes);
        }
        return admin.client().post("/api/console/invitations").json(body).expect(201);
    }

    /**
     * Invites and accepts, with the link from the response (SMTP off) or from {@code linkOf}, and
     * returns the person signed in on their app.
     */
    Person join(Person admin, String role, List<String> companyCodes, Function<String, String> linkOf) {
        String email = Unique.email(role.toLowerCase());
        ApiResponse invitation = invite(admin, role, email, companyCodes);
        String link = linkOf == null ? invitation.jsonText("inviteLink") : linkOf.apply(email);
        boolean customer = role.equals("CUSTOMER");
        ApiClient client = customer ? stack.portal() : stack.console();
        client.post("/api/public/invitations/accept")
                .json(Map.of("token", Fixtures.tokenOf(link), "password", Fixtures.PASSWORD))
                .expect(204);
        return new Person(client, customer ? "/api/portal" : "/api/console", invitation.jsonLong("accountId"), email,
                Fixtures.PASSWORD);
    }

    /** Files a public issue with a unique title on the portal; returns its key. */
    String fileIssue(Person customer, String companyCode) {
        return customer.client().post("/api/portal/issues")
                .json(Map.of("companyCode", companyCode, "title", Unique.text("Issue"), "content", "Steps to reproduce."))
                .expect(201)
                .jsonText("key");
    }

    /** Uploads a file to an issue; returns the attachment ID. */
    String attach(Person person, String issueKey, String fileName, byte[] content) {
        ApiRequest request = person.client().post(person.api() + "/issues/" + issueKey + "/attachments")
                .file("file", fileName, "application/octet-stream", content);
        return request.expect(201).jsonText("id");
    }

    ApiResponse download(Person person, String issueKey, String attachmentId) {
        return person.client().get(person.api() + "/issues/" + issueKey + "/attachments/" + attachmentId).send();
    }
}
