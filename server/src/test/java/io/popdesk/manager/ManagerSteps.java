package io.popdesk.manager;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the customer manager tests of slice 2: making a manager through the console API (an admin
 * invites a customer, the customer accepts, the admin marks them), the paths of the member and
 * membership request endpoints, the scene most tests start from, reading and changing the member list,
 * and comparing two responses that must not tell two cases apart.
 *
 * <p>Each step that reaches a state expects its one status, so before the slice is built a test fails
 * there, on an assertion. The steps under "Console steps" do what {@code project.PeopleSteps} and
 * {@code project.ProjectChecks} do; those are not visible from this package.
 */
public final class ManagerSteps {

    /**
     * A row ID as the member list gives it: a random UUID (version 4, design 0-2) in lower-case hex. An
     * ID made from an account ID or a request number (a counter, a version 5 UUID) does not match.
     */
    public static final Pattern ROW_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    /**
     * Headers that differ from one request to the next whatever the case: the time of the answer, and
     * what the server says about the connection (it closes one after a number of requests, so the
     * same request is answered with or without {@code Connection: close}).
     */
    private static final List<String> HEADERS_NOT_COMPARED = List.of("date", "connection", "keep-alive");

    private ManagerSteps() {}

    // Paths

    /** The member list of a company on the portal; POST invites. */
    public static String members(String code) {
        return "/api/portal/companies/" + code + "/members";
    }

    /** One row of the member list; DELETE takes it out. */
    public static String member(String code, String rowId) {
        return members(code) + "/" + rowId;
    }

    /** POST sends the row's invitation again. */
    public static String resend(String code, String rowId) {
        return member(code, rowId) + "/resend";
    }

    /** PUT marks the customer as a manager of the company, DELETE takes the mark away (console). */
    public static String managerOf(String code, long accountId) {
        return customerOf(code, accountId) + "/manager";
    }

    public static String customerOf(String code, long accountId) {
        return "/api/console/companies/" + code + "/customers/" + accountId;
    }

    // Scenes

    /** An admin, a company with a manager, and another company the manager does not belong to. */
    public record Scene(Session admin, Company company, Company other, Session manager) {

        public String code() {
            return company.code();
        }
    }

    /** A scene the first admin made. */
    public static Scene scene(Fixtures fixtures) {
        return scene(fixtures, fixtures.signInAdmin());
    }

    /** A scene this admin made. */
    public static Scene scene(Fixtures fixtures, Session admin) {
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        return new Scene(admin, company, other, manager(fixtures, admin, company.code()));
    }

    // Managers

    /** A signed-in customer of {@code code} that an admin marked as its manager. */
    public static Session manager(Fixtures fixtures, Session admin, String code) {
        Session customer = fixtures.customer(admin, code);
        mark(admin, code, customer.accountId());
        return customer;
    }

    /** Marks a customer of the company as its manager; the response is the console's member entry. */
    public static ApiResponse mark(Session admin, String code, long accountId) {
        return admin.client().put(managerOf(code, accountId)).expect(200);
    }

    public static ApiResponse unmark(Session admin, String code, long accountId) {
        return admin.client().delete(managerOf(code, accountId)).expect(200);
    }

    /** The manager mark of a console customer entry, a member row, or a company of the portal. */
    public static boolean managerFlag(JsonNode entry) {
        assertThat(entry).as("the entry that carries the manager flag").isNotNull();
        assertThat(entry.path("manager").isBoolean()).as("manager is a boolean in " + entry).isTrue();
        return entry.path("manager").booleanValue();
    }

    // Members, as a manager

    /** Invites with this email and name, whatever the answer. */
    public static ApiResponse invite(Session manager, String code, String email, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", name);
        return manager.client().post(members(code)).json(body).send();
    }

    /** Invites and expects the 204. */
    public static void invited(Session manager, String code, String email, String name) {
        invite(manager, code, email, name).expectStatus(204);
    }

    /** Every row of the company's member list, in the order the list gives them. */
    public static List<JsonNode> rows(Session manager, String code) {
        return pages(manager.client(), members(code));
    }

    /** The emails of the member list, in its order. */
    public static List<String> emails(Session manager, String code) {
        return rows(manager, code).stream().map(r -> r.path("email").asString(null)).toList();
    }

    /** The one row with this email (ignoring case). Fails when there is none or more than one. */
    public static JsonNode row(Session manager, String code, String email) {
        List<JsonNode> found = rows(manager, code).stream()
                .filter(r -> email.equalsIgnoreCase(r.path("email").asString(null)))
                .toList();
        assertThat(found).as("rows for %s in the member list of %s", email, code).hasSize(1);
        return found.getFirst();
    }

    /** The row ID of the one row with this email. */
    public static String rowId(Session manager, String code, String email) {
        JsonNode id = row(manager, code, email).path("id");
        assertThat(id.isString()).as("id of the row for %s is text", email).isTrue();
        return id.stringValue();
    }

    /** Takes the row with this email out of the member list, whatever the answer. */
    public static ApiResponse remove(Session manager, String code, String email) {
        return manager.client().delete(member(code, rowId(manager, code, email))).send();
    }

    /** Sends the invitation of the row with this email again, whatever the answer. */
    public static ApiResponse resendTo(Session manager, String code, String email) {
        return manager.client().post(resend(code, rowId(manager, code, email))).send();
    }

    /**
     * Has the manager invite a new email and waits for that mail: every mail queued before is out by
     * then. Returns the answer to that invitation, a 204.
     */
    public static ApiResponse awaitALaterMail(Scene s, MailServer mail) {
        String marker = Unique.email("marker");
        ApiResponse response = invite(s.manager(), s.code(), marker, "Marker");
        response.expectStatus(204);
        mail.awaitMessageTo(marker);
        return response;
    }

    // Membership requests, as an admin

    /** The pending membership requests of one company, oldest first. */
    public static List<JsonNode> requests(Session admin, String code) {
        return pages(admin.client(), "/api/console/membership-requests", "companyCode", code);
    }

    // Existing accounts a manager may type the email of

    /**
     * An account that already exists when a manager invites its email. {@code token} is the token of
     * its invitation link when it has not accepted yet, otherwise null.
     */
    public record Existing(long accountId, String email, String name, String token) {}

    /** The kinds of account a manager's invitation must not tell apart from a new email. */
    public enum ExistingKind {
        CUSTOMER_OF_ANOTHER_COMPANY,
        AGENT,
        ADMIN,
        SUSPENDED_CUSTOMER,
        INVITED_CUSTOMER,
        CUSTOMER_OF_NO_COMPANY,
        SUSPENDED_AGENT;

        /** Makes such an account; the customers belong to {@code otherCode} only, or to no company. */
        public Existing make(Fixtures fixtures, Session admin, String otherCode) {
            return switch (this) {
                case CUSTOMER_OF_ANOTHER_COMPANY -> existing(fixtures.customer(admin, otherCode));
                case AGENT -> existing(fixtures.agent(admin));
                case ADMIN -> existing(fixtures.admin(admin));
                case SUSPENDED_CUSTOMER -> {
                    Session customer = fixtures.customer(admin, otherCode);
                    suspend(admin, customer.accountId());
                    yield existing(customer);
                }
                case INVITED_CUSTOMER -> {
                    Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, otherCode);
                    yield new Existing(invitation.accountId(), invitation.email(), invitation.name(),
                            invitation.token());
                }
                case CUSTOMER_OF_NO_COMPANY -> {
                    Session customer = fixtures.customer(admin, otherCode);
                    removeCustomer(admin, otherCode, customer.accountId());
                    yield existing(customer);
                }
                case SUSPENDED_AGENT -> {
                    Session agent = fixtures.agent(admin);
                    suspend(admin, agent.accountId());
                    yield existing(agent);
                }
            };
        }

        private static Existing existing(Session session) {
            return new Existing(session.accountId(), session.email(), session.name(), null);
        }
    }

    /** Lower-case emails, at most 26, whose order as returned is the same under any collation. */
    public static List<String> orderedEmails(int count) {
        String stem = "m" + Unique.number();
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            emails.add(stem + (char) ('a' + i) + "@example.test");
        }
        return emails;
    }

    // Checks

    /** Checks status and {@code code} of an error body. */
    public static void assertError(ApiResponse response, int status, String code) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
        assertThat(response.errorCode()).as("code of " + response.request()).isEqualTo(code);
    }

    /** The {@code field:code} pairs of the {@code fields} of a 400 {@code INVALID_INPUT}. */
    public static List<String> fieldErrors(ApiResponse response) {
        List<String> pairs = new ArrayList<>();
        response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        return pairs;
    }

    /**
     * "The same response": status, the whole body, and every header, so one that only one of two cases
     * carries shows. Left out is only what differs between two requests of the same case: {@code Date},
     * the headers about the connection, and the values of {@code Set-Cookie}, of which the names and
     * attributes are compared (a token that is random in both cases tells nothing). Response time is not
     * compared.
     */
    public static void assertSameResponse(ApiResponse expected, ApiResponse actual) {
        String what = actual.request() + " compared with " + expected.request();
        assertThat(actual.status()).as("status of " + what).isEqualTo(expected.status());
        assertThat(actual.text()).as("body of " + what).isEqualTo(expected.text());
        assertThat(comparedHeaders(actual)).as("headers of " + what).isEqualTo(comparedHeaders(expected));
    }

    private static Map<String, List<String>> comparedHeaders(ApiResponse response) {
        Map<String, List<String>> headers = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("set-cookie")) {
                headers.put(lower, cookieShapes(response));
            } else if (!HEADERS_NOT_COMPARED.contains(lower) && !lower.startsWith(":")) {
                headers.put(lower, values);
            }
        });
        return headers;
    }

    private static List<String> cookieShapes(ApiResponse response) {
        return response.headers("Set-Cookie").stream()
                .map(c -> c.replaceFirst("=[^;]*", "=").toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    // Console steps

    /** Every item of a COM-4 list, page by page with {@code size=100}; {@code query} is name, value, ... */
    public static List<JsonNode> pages(ApiClient client, String path, Object... query) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiRequest request = client.get(path).query("page", page).query("size", 100);
            for (int i = 0; i < query.length; i += 2) {
                request.query((String) query[i], query[i + 1]);
            }
            ApiResponse response = request.expect(200);
            JsonNode items = response.json().path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError(path + " has more than 1000 pages");
    }

    /** The customers of a company as the console lists them. */
    public static List<JsonNode> customers(Session admin, String code) {
        return pages(admin.client(), "/api/console/companies/" + code + "/customers");
    }

    /** The console's entry of one customer of a company. Fails when the account is not in the list. */
    public static JsonNode customer(Session admin, String code, long accountId) {
        return customers(admin, code).stream()
                .filter(c -> c.path("accountId").asLong(-1) == accountId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Account " + accountId + " is not a customer of " + code));
    }

    public static List<Long> customerIds(Session admin, String code) {
        return customers(admin, code).stream().map(c -> c.path("accountId").asLong(-1)).toList();
    }

    /** The entries of the admin's pending invitation list with this email. */
    public static List<JsonNode> pendingInvitations(Session admin, String email) {
        return pages(admin.client(), "/api/console/invitations").stream()
                .filter(n -> email.equalsIgnoreCase(n.path("email").asString(null)))
                .toList();
    }

    public static void addCustomer(Session admin, String code, long accountId) {
        admin.client().put(customerOf(code, accountId)).expect(200);
    }

    public static void removeCustomer(Session admin, String code, long accountId) {
        admin.client().delete(customerOf(code, accountId)).expect(200);
    }

    public static void suspend(Session admin, long accountId) {
        admin.client().post("/api/console/accounts/" + accountId + "/suspend").expect(200);
    }

    /** The IDs of the customer accounts with this status, as the console's account list gives them. */
    public static List<Long> customerAccounts(Session admin, String status) {
        return pages(admin.client(), "/api/console/accounts", "role", "CUSTOMER", "status", status).stream()
                .map(a -> a.path("id").asLong(-1)).toList();
    }

    /** {@code SUSPENDED}, {@code ARCHIVED}, or {@code ACTIVE}. */
    public static void setCompanyStatus(Session admin, String code, String status) {
        admin.client().put("/api/console/companies/" + code + "/status").json(Map.of("status", status)).expect(200);
    }

    /** Accepts with the token of an invitation link on the portal, whatever the answer. */
    public static ApiResponse acceptOnPortal(ApiClient portal, String token) {
        return portal.post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", Fixtures.PASSWORD)).send();
    }

    /** The companies of {@code GET /api/portal/me}, by code. */
    public static Map<String, JsonNode> myCompanies(Session customer) {
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        customer.client().get("/api/portal/me").expect(200).json().path("companies")
                .forEach(c -> byCode.put(c.path("code").asString(null), c));
        return byCode;
    }
}
