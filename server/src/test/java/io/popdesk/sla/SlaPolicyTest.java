package io.popdesk.sla;

import static io.popdesk.due.DueSteps.assertRefused;
import static io.popdesk.sla.SlaSteps.canonical;
import static io.popdesk.sla.SlaSteps.changeRequest;
import static io.popdesk.sla.SlaSteps.json;
import static io.popdesk.sla.SlaSteps.policy;
import static io.popdesk.sla.SlaSteps.policyRequest;
import static io.popdesk.sla.SlaSteps.version;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Changing the SLA policy (S8A-AC-02 to S8A-AC-05): a new version, the same values keep the version, a stale
 * {@code version} is 409, wrong values are 400 per field (design.md 5절), and only admins read and change it. Every
 * policy a test sends holds a holiday no other test sends, so it differs from whatever the policy is when the test
 * starts.
 */
class SlaPolicyTest extends SlaAppTest {

    /** A day in 2027 or later that no other test of this JVM picks. */
    static LocalDate uniqueDay() {
        return LocalDate.of(2027, 1, 1).plusDays(Unique.number() % 20_000);
    }

    /** A policy with goals, business hours, and two holidays, one of them {@link #uniqueDay()}. */
    static Policy somePolicy() {
        return Policy.off()
                .target("NORMAL", 60, 480)
                .target("URGENT", 15, 120)
                .target("HIGH", null, 240)
                .weekdays("09:00", "18:00")
                .day(DayOfWeek.SATURDAY, "10:00", "24:00")
                .holiday(LocalDate.of(2026, 12, 25))
                .holiday(uniqueDay());
    }

    private static List<Object> reversed(List<?> list) {
        List<Object> out = new ArrayList<>(list);
        Collections.reverse(out);
        return out;
    }

    private void assertHolds(JsonNode policy, Policy expected, String what) {
        assertThat(canonical(policy.path("targets"))).as("targets of %s", what)
                .isEqualTo(canonical(json(expected.targets())));
        assertThat(canonical(policy.path("hours"))).as("hours of %s", what)
                .isEqualTo(canonical(json(expected.hours())));
        assertThat(canonical(policy.path("holidays"))).as("holidays of %s", what)
                .isEqualTo(canonical(json(expected.holidays())));
    }

    // S8A-AC-02

    @Test
    @DisplayName("S8A-AC-02 a change makes the next version and answers 200 with the new policy: goals LOW first, "
            + "weekdays Monday first, holidays earliest first, whatever order was sent, changed by the admin at the "
            + "app's time; sending the same values again keeps the version and answers the policy as it is")
    void changeMakesTheNextVersion() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Policy wanted = somePolicy();
        int before = version(policy(admin));
        Map<String, Object> shuffled = new LinkedHashMap<>(wanted.body(before));
        shuffled.put("targets", reversed(wanted.targets()));
        shuffled.put("hours", reversed(wanted.hours()));
        shuffled.put("holidays", reversed(wanted.holidays()));
        Instant at = flow.tick();

        JsonNode changed = changeRequest(admin, shuffled).expect(200).json();

        assertThat(version(changed)).as("version of the answer").isEqualTo(before + 1);
        assertHolds(changed, wanted, "the answer");
        assertThat(changed.path("timeZone").asString(null)).as("timeZone").isEqualTo(SEOUL);
        assertThat(changed.path("changedBy").path("accountId").asLong(-1)).as("changedBy of %s", changed)
                .isEqualTo(admin.accountId());
        assertThat(IssueFlow.time(changed, "changedAt")).as("changedAt").isEqualTo(at);
        assertThat(canonical(policy(admin))).as("the policy read again").isEqualTo(canonical(changed));

        flow.tick();
        Map<String, Object> again = new LinkedHashMap<>(wanted.body(before + 1));
        again.put("holidays", reversed(wanted.holidays()));
        JsonNode same = changeRequest(admin, again).expect(200).json();

        assertThat(canonical(same)).as("the answer to the same values").isEqualTo(canonical(changed));
        assertThat(version(policy(admin))).as("version after the same values").isEqualTo(before + 1);
    }

    // S8A-AC-03

    @Test
    @DisplayName("S8A-AC-03 two changes sent with the same version: the first makes the next version, the second is "
            + "409 VERSION_CONFLICT and changes nothing")
    void staleVersionIsAConflict() {
        Session admin = fixtures.signInAdmin();
        Session otherAdmin = fixtures.admin(admin);
        Policy first = somePolicy();
        Policy second = somePolicy().target("LOW", 600, 6000);
        int read = version(policy(admin));

        JsonNode saved = changeRequest(admin, first.body(read)).expect(200).json();
        ApiResponse late = changeRequest(otherAdmin, second.body(read)).send();

        assertThat(version(saved)).as("version of the first change").isEqualTo(read + 1);
        assertRefused(late, 409, "VERSION_CONFLICT");
        JsonNode now = policy(admin);
        assertThat(version(now)).as("version after the conflict").isEqualTo(read + 1);
        assertHolds(now, first, "the policy after the conflict");
        assertThat(now.path("changedBy").path("accountId").asLong(-1)).as("changedBy after the conflict")
                .isEqualTo(admin.accountId());
    }

    // S8A-AC-04

    private static Map<String, Object> at(Map<String, Object> body, String list, int index) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get(list);
        return items.get(index);
    }

    private static Arguments refused(String name, Consumer<Map<String, Object>> change, String field, String code) {
        return Arguments.of(name, change, field, code);
    }

    static Stream<Arguments> wrongValues() {
        return Stream.of(
                refused("a first response goal of 0", b -> at(b, "targets", 1).put("firstResponseMinutes", 0),
                        "targets[1].firstResponseMinutes", "OUT_OF_RANGE"),
                refused("a resolution goal of 525,601", b -> at(b, "targets", 2).put("resolutionMinutes", 525_601),
                        "targets[2].resolutionMinutes", "OUT_OF_RANGE"),
                refused("a resolution goal shorter than the first response goal",
                        b -> at(b, "targets", 1).put("resolutionMinutes", 59),
                        "targets[1].resolutionMinutes", "OUT_OF_RANGE"),
                refused("three priorities", b -> ((List<?>) b.get("targets")).remove(3), "targets", "UNKNOWN_VALUE"),
                refused("a priority twice", b -> at(b, "targets", 3).put("priority", "HIGH"), "targets",
                        "UNKNOWN_VALUE"),
                refused("six weekdays", b -> ((List<?>) b.get("hours")).remove(6), "hours", "UNKNOWN_VALUE"),
                refused("a weekday twice", b -> at(b, "hours", 6).put("weekday", "MONDAY"), "hours", "UNKNOWN_VALUE"),
                refused("opens without closes", b -> at(b, "hours", 0).put("closes", null), "hours[0].closes",
                        "REQUIRED"),
                refused("closes without opens", b -> at(b, "hours", 6).put("closes", "12:00"), "hours[6].opens",
                        "REQUIRED"),
                refused("a time without its leading zero", b -> at(b, "hours", 1).put("opens", "9:00"),
                        "hours[1].opens", "INVALID_FORMAT"),
                refused("a time with seconds", b -> at(b, "hours", 1).put("closes", "18:00:30"), "hours[1].closes",
                        "INVALID_FORMAT"),
                refused("opens at 24:00", b -> {
                    at(b, "hours", 2).put("opens", "24:00");
                    at(b, "hours", 2).put("closes", "24:00");
                }, "hours[2].opens", "INVALID_FORMAT"),
                refused("closes at opens", b -> at(b, "hours", 3).put("closes", "09:00"), "hours[3].closes",
                        "OUT_OF_RANGE"),
                refused("closes before opens", b -> at(b, "hours", 4).put("closes", "08:00"), "hours[4].closes",
                        "OUT_OF_RANGE"),
                refused("501 holidays", b -> {
                    List<String> days = new ArrayList<>();
                    for (int i = 0; i < 501; i++) {
                        days.add(LocalDate.of(2030, 1, 1).plusDays(i).toString());
                    }
                    b.put("holidays", days);
                }, "holidays", "TOO_MANY"),
                refused("a day twice", b -> b.put("holidays", List.of("2026-12-25", "2026-12-31", "2026-12-25")),
                        "holidays[2]", "UNKNOWN_VALUE"),
                refused("no version", b -> b.remove("version"), "version", "REQUIRED"),
                refused("no targets", b -> b.remove("targets"), "targets", "REQUIRED"),
                refused("hours null", b -> b.put("hours", null), "hours", "REQUIRED"),
                refused("holidays null", b -> b.put("holidays", null), "holidays", "REQUIRED"),
                refused("a day that does not exist", b -> b.put("holidays", List.of("2026-13-01")), "holidays",
                        "INVALID_FORMAT"),
                refused("a goal that is not a number", b -> at(b, "targets", 1).put("firstResponseMinutes", "sixty"),
                        "targets", "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongValues")
    @DisplayName("S8A-AC-04 wrong values are 400 INVALID_INPUT with the field and its code of design.md 5절, and "
            + "change nothing")
    void wrongValuesChangeNothing(String name, Consumer<Map<String, Object>> wrong, String field, String code) {
        Session admin = fixtures.signInAdmin();
        JsonNode before = policy(admin);
        Map<String, Object> body = somePolicy().body(version(before));
        wrong.accept(body);

        ApiResponse refused = changeRequest(admin, body).send();

        assertRefused(refused, 400, "INVALID_INPUT");
        List<String> fields = new ArrayList<>();
        refused.json().path("fields").forEach(f -> fields.add(f.path("field").asString(null) + " "
                + f.path("code").asString(null)));
        assertThat(fields).as("fields of %s", refused.snippet()).contains(field + " " + code);
        assertThat(canonical(policy(admin))).as("the policy after the refusal").isEqualTo(canonical(before));
    }

    // S8A-AC-05

    @Test
    @DisplayName("S8A-AC-05 an agent gets 403 FORBIDDEN for reading and for changing the policy, which stays as it "
            + "was; an admin reads it")
    void onlyAdminsReadAndChange() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin, fixtures.createCompany(admin).code());
        JsonNode before = policy(admin);

        ApiResponse read = policyRequest(agent).send();
        ApiResponse changed = changeRequest(agent, somePolicy().body(version(before))).send();

        assertRefused(read, 403, "FORBIDDEN");
        assertRefused(changed, 403, "FORBIDDEN");
        assertThat(canonical(policy(admin))).as("the policy after the agent's change").isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S8A-AC-05 a customer gets what any console path gives them: 404 on the portal host, 401 on the "
            + "console host, for reading and for changing the policy")
    void customerGetsTheConsoleAnswer() {
        Session admin = fixtures.signInAdmin();
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);
        Map<String, Object> body = Policy.off().body(1);

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/companies").send();
            for (ApiResponse answer : List.of(client.get(SlaSteps.POLICY).send(),
                    client.put(SlaSteps.POLICY).json(body).send())) {
                assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet())
                        .isEqualTo(existing.status());
                assertThat(answer.errorCode()).as("code of " + answer.snippet()).isEqualTo(existing.errorCode());
            }
        }
        assertThat(customer.client().get(SlaSteps.POLICY).send().status()).as("on the portal host").isEqualTo(404);
        assertThat(carried.get(SlaSteps.POLICY).send().status()).as("on the console host").isEqualTo(401);
    }
}
