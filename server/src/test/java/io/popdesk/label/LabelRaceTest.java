package io.popdesk.label;

import static io.popdesk.label.LabelSteps.body;
import static io.popdesk.label.LabelSteps.color;
import static io.popdesk.label.LabelSteps.id;
import static io.popdesk.label.LabelSteps.labelIds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Requests that arrive at the same time (TM-56, {@code design.md} 0절 1 and 2절): creating one name in several
 * cases, creating past the 200 labels of a company, and adding past the 20 labels of an issue. Only the number
 * of each answer is counted, so it does not matter which request wins. Each test makes its own company.
 */
class LabelRaceTest extends BareAppTest {

    private static final int AT_ONCE = 8;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The statuses of the answers, with the error codes of those that are not 2xx, for the failure message. */
    private static String describe(List<ApiResponse> answers) {
        return answers.stream().map(a -> a.status() + (a.status() < 300 ? "" : " " + a.errorCode())).toList()
                .toString();
    }

    private static void assertOneWins(List<ApiResponse> answers, int won, int status, String code) {
        assertThat(answers.stream().filter(a -> a.status() == won).count()).as("answers %d in %s", won,
                describe(answers)).isEqualTo(1);
        assertThat(answers.stream().filter(a -> a.status() == status && code.equals(a.errorCode())).count())
                .as("answers %d %s in %s", status, code, describe(answers)).isEqualTo(answers.size() - 1L);
    }

    /** {@code name} with the letters chosen by the bits of {@code mask} in upper case and the rest in lower case. */
    private static String cased(String name, int mask) {
        StringBuilder out = new StringBuilder();
        int letter = 0;
        for (char c : name.toCharArray()) {
            if (Character.isLetter(c)) {
                out.append((mask >> letter & 1) == 1 ? Character.toUpperCase(c) : Character.toLowerCase(c));
                letter++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // S6A-AC-03

    @Test
    @DisplayName("S6A-AC-03 one name in 8 cases created at the same time: exactly one is 201, the others are 409 LABEL_NAME_TAKEN, and the company has that name once")
    void sameNameAtOnce() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        String name = LabelSteps.name("urgent");
        List<String> names = new ArrayList<>();
        for (int mask = 0; mask < AT_ONCE; mask++) {
            names.add(cased(name, mask * 9));
        }
        assertThat(names).as("names sent").doesNotHaveDuplicates();
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (String each : names) {
            calls.add(() -> LabelSteps.createRequest(admin, company.code(), body(each, color())).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertOneWins(answers, 201, 409, "LABEL_NAME_TAKEN");
        assertThat(LabelSteps.list(admin, company.code()).stream()
                .map(l -> l.path("name").asString("").toLowerCase(Locale.ROOT)).toList())
                .as("names of the company's labels, in lower case").containsExactly(name.toLowerCase(Locale.ROOT));
    }

    // S6A-AC-09

    @Test
    @DisplayName("S6A-AC-09 with 199 labels in a company, 8 different names created at the same time: exactly one is 201, the others are 400 LABEL_LIMIT_EXCEEDED, and the company has 200")
    void lastLabelOfTheCompanyAtOnce() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        for (int i = 0; i < 199; i++) {
            LabelSteps.create(admin, company.code(), "L" + i, color());
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            String name = "Late " + i;
            calls.add(() -> LabelSteps.createRequest(admin, company.code(), body(name, color())).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertOneWins(answers, 201, 400, "LABEL_LIMIT_EXCEEDED");
        assertThat(LabelSteps.list(admin, company.code())).as("labels of the company").hasSize(200);
    }

    // S6A-AC-24

    @Test
    @DisplayName("S6A-AC-24 with 19 labels on an issue, 8 different labels added at the same time: exactly one is 200, the others are 400 ISSUE_LABEL_LIMIT_EXCEEDED, and the issue has 20")
    void lastLabelOfTheIssueAtOnce() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        List<Long> on = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            long label = id(LabelSteps.create(admin, company.code()));
            LabelSteps.add(agent, issue.key(), label);
            on.add(label);
        }
        List<Long> racing = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            racing.add(id(LabelSteps.create(admin, company.code())));
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (long label : racing) {
            calls.add(() -> LabelSteps.addRequest(agent, issue.key(), label).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertOneWins(answers, 200, 400, "ISSUE_LABEL_LIMIT_EXCEEDED");
        JsonNode read = flow.detail(agent, issue.key());
        assertThat(labelIds(read)).as("labels of the issue read again").hasSize(20).containsAll(on)
                .containsAnyElementsOf(racing);
    }
}
