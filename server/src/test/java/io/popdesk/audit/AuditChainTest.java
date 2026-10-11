package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.assertChain;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.export;
import static io.popdesk.audit.AuditSteps.hash;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.ChainSteps.chain;
import static io.popdesk.support.AuditChainMath.HASH_PATTERN;
import static io.popdesk.support.AuditChainMath.assertValid;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.project.PeopleSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.FailedWrite;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The hash chain of the audit log, in the context every bare test shares: the hash of a new entry,
 * many entries at once, a change that is rolled back, and the integrity check on a chain nobody
 * touched. Other tests' entries are in the same chain, so these tests check the whole chain as it
 * is and find their own entries by the people and companies they made.
 *
 * <p>Tests that break the chain in the database are in {@link AuditTamperTest}.
 */
class AuditChainTest extends BareAppTest {

    private static final int AT_ONCE = 30;

    private static final String NEW_PASSWORD = "chain-Password-5k";

    /** The kinds of entry a test can cause over HTTP with SMTP off: all but the first admin and the commands. */
    private static final List<String> KINDS = List.of("SIGN_IN_SUCCEEDED", "SIGN_IN_FAILED", "ACCOUNT_LOCKED",
            "ACCOUNT_NAME_CHANGED", "ACCOUNT_ROLE_CHANGED", "ACCOUNT_SUSPENDED", "ACCOUNT_REACTIVATED",
            "ACCOUNT_PASSWORD_RESET", "PASSWORD_CHANGED", "PASSWORD_RESET_COMPLETED", "INVITATION_CREATED",
            "INVITATION_RESENT", "INVITATION_CANCELLED", "INVITATION_LINK_CREATED", "COMPANY_CREATED",
            "COMPANY_UPDATED", "COMPANY_STATUS_CHANGED", "CUSTOMER_ADDED", "CUSTOMER_REMOVED", "AGENT_ASSIGNED",
            "AGENT_UNASSIGNED", "AUDIT_EXPORTED");

    /** Where a change fails after its audit entry was recorded. */
    enum Failure {
        /** A statement after the one that records the entry fails: a suspension, ending the account's sessions. */
        WHILE_WRITING,
        /** Everything was written and the commit itself fails (a deferred constraint): creating a company. */
        AT_COMMIT
    }

    /** A change that failed: the query that would find its entry in the log, and its line in an export. */
    private record RolledBack(Object[] query, Predicate<JsonNode> isItsLine) {}

    private static String accountPath(long accountId) {
        return "/api/console/accounts/" + accountId;
    }

    private static String companyPath(String code) {
        return "/api/console/companies/" + code;
    }

    @Test
    @DisplayName("S1B-AC-09 the hash of a new entry is SHA-256 of the previous entry's hash and the entry's "
            + "canonical JSON, computed again from the API's answer alone")
    void hashIsComputedFromThePreviousHashAndTheCanonicalJson() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        String name = "사슬 \"회사\" " + Unique.number();
        Company company = fixtures.createCompany(admin, Unique.companyCode(), name, name + " (주)");

        JsonNode entry = onlyEntry(admin, "targetCode", company.code(), "action", "COMPANY_CREATED");

        long seq = number(entry, "seq");
        List<JsonNode> before = chain(admin).stream().filter(line -> number(line, "seq") == seq - 1).toList();
        assertThat(text(entry, "hash")).as("hash of %s", entry).matches(HASH_PATTERN);
        assertThat(text(entry, "prevHash")).as("prevHash of %s", entry).matches(HASH_PATTERN);
        assertThat(text(entry, "hash")).as("hash of %s computed again from %s", entry, AuditSteps.chainJson(entry))
                .isEqualTo(hash(text(entry, "prevHash"), entry));
        assertThat(before).as("the entry before, seq %d", seq - 1).hasSize(1);
        assertThat(text(entry, "prevHash")).as("prevHash of seq %d is the hash of seq %d", seq, seq - 1)
                .isEqualTo(text(before.getFirst(), "hash"));
    }

    @Test
    @DisplayName("S1B-AC-10 " + AT_ONCE + " sign-ins of different accounts at once all succeed and stay one "
            + "chain: seq without a gap, no previous hash twice, every hash computes again, and the integrity "
            + "check says valid")
    void entriesAtOnceStayOneChain() throws InterruptedException {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        List<Session> people = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            people.add(fixtures.agent(admin));
        }
        Set<Long> accountIds = new TreeSet<>(people.stream().map(Session::accountId).toList());
        Instant from = clock.advance(Duration.ofSeconds(1));
        List<Integer> statuses = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(AT_ONCE);
        try {
            List<Future<Integer>> signIns = new ArrayList<>();
            for (Session person : people) {
                signIns.add(threads.submit(() -> {
                    go.await();
                    return console().post("/api/public/sign-in")
                            .json(Map.of("email", person.email(), "password", person.password())).send().status();
                }));
            }
            go.countDown();
            for (Future<Integer> signIn : signIns) {
                try {
                    statuses.add(signIn.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException e) {
                    throw new AssertionError("A sign-in sent with " + (AT_ONCE - 1) + " others did not end with a "
                            + "response: " + e, e);
                }
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(statuses).as("statuses of %d sign-ins sent at once", AT_ONCE).hasSize(AT_ONCE).containsOnly(204);

        List<JsonNode> chain = chain(admin);

        assertThat(chain.stream().filter(line -> !AuditSteps.time(line).isBefore(from))
                .filter(line -> "SIGN_IN_SUCCEEDED".equals(line.path("action").asString(null)))
                .map(line -> line.path("actor").path("accountId").asLong(-1)).filter(accountIds::contains).toList())
                .as("accounts of the SIGN_IN_SUCCEEDED entries added by the %d sign-ins", AT_ONCE)
                .containsExactlyInAnyOrderElementsOf(accountIds);
        assertChain(chain);
        assertThat(chain.stream().map(line -> text(line, "prevHash")).toList()).as("previous hashes of the chain")
                .doesNotHaveDuplicates();
        assertValid(admin.client());
    }

    private static void assertInternalError(ApiResponse failed) {
        assertThat(failed.status()).as("%s: %s", failed, failed.snippet()).isEqualTo(500);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    /**
     * Suspends an agent while the statement that ends the agent's sessions fails. The suspension
     * records its entry before that statement, so the entry was recorded when the transaction broke.
     */
    private RolledBack suspensionFailsEndingTheSessions(Session admin) {
        Session agent = fixtures.agent(admin);
        ApiResponse failed;
        try (FailedWrite failure = FailedWrite.statement(db, "spring_session", "delete",
                Long.toString(agent.accountId()))) {
            failed = admin.client().post(accountPath(agent.accountId()) + "/suspend").send();
        }
        assertInternalError(failed);
        assertThat(agent.client().get("/api/console/me").expect(200).jsonLong("id"))
                .as("the agent is still signed in: the suspension was rolled back").isEqualTo(agent.accountId());
        return new RolledBack(new Object[] {"targetAccountId", agent.accountId(), "action", "ACCOUNT_SUSPENDED"},
                line -> "ACCOUNT_SUSPENDED".equals(line.path("action").asString(null))
                        && line.path("target").path("accountId").asLong(-1) == agent.accountId());
    }

    /** Creates a company whose rows are all written and whose commit then fails. */
    private RolledBack companyFailsAtCommit(Session admin) {
        String code = Unique.companyCode();
        String legalName = Unique.text("Rolled back Ltd.");

        ApiResponse failed = ChainSteps.createCompanyFailingAtCommit(db, fixtures, admin, code, legalName);

        assertInternalError(failed);
        admin.client().get(companyPath(code)).expect(404);
        return new RolledBack(new Object[] {"targetCode", code}, line -> line.toString().contains(legalName));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("S1B-AC-11 a change that fails after its entry was recorded and is rolled back leaves no entry "
            + "and takes no place in the chain: the next entry follows without a gap and the integrity check says "
            + "valid")
    void rolledBackChangeLeavesNoEntryAndNoGap(Failure failure) {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        RolledBack rolledBack = switch (failure) {
            case WHILE_WRITING -> suspensionFailsEndingTheSessions(admin);
            case AT_COMMIT -> companyFailsAtCommit(admin);
        };

        Company next = fixtures.createCompany(admin);

        List<JsonNode> chain = chain(admin);
        assertThat(entries(admin, rolledBack.query())).as("entries of the change that was rolled back").isEmpty();
        assertThat(chain.stream().filter(rolledBack.isItsLine()).toList())
                .as("lines of the export from the change that was rolled back").isEmpty();
        assertThat(chain.stream().filter(line -> next.code().equals(line.path("target").path("code").asString(null)))
                .toList()).as("lines of the company created after the failure").hasSize(1);
        assertChain(chain);
        assertValid(admin.client());
    }

    @Test
    @DisplayName("S1B-AC-14 after every kind of entry a request can cause, the integrity check computes the "
            + "chain again and says valid: a count above zero, no reason, no broken entry")
    void everyKindOfEntryKeepsTheChainValid() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, other.code());
        admin.client().put(companyPath(company.code()) + "/customers/" + customer.accountId()).expect(200);
        admin.client().delete(companyPath(company.code()) + "/customers/" + customer.accountId()).expect(200);
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        fixtures.assignAgent(admin, company.code(), invited.accountId());
        admin.client().delete(companyPath(company.code()) + "/agents/" + invited.accountId()).expect(200);
        admin.client().post("/api/console/invitations/" + invited.accountId() + "/resend").expect(200);
        admin.client().post("/api/console/invitations/" + invited.accountId() + "/link").expect(200);
        admin.client().post("/api/console/invitations/" + fixtures.invite(admin, Role.AGENT).accountId() + "/cancel")
                .expect(204);
        Session agent = fixtures.agent(admin);
        admin.client().patch(accountPath(agent.accountId())).json(Map.of("name", Unique.text("Renamed"))).expect(200);
        admin.client().post(accountPath(agent.accountId()) + "/suspend").expect(200);
        admin.client().post(accountPath(agent.accountId()) + "/reactivate").expect(200);
        admin.client().put(accountPath(agent.accountId()) + "/role").json(Map.of("role", "ADMIN")).expect(200);
        Session member = fixtures.agent(admin);
        member.client().put("/api/console/me/password")
                .json(Map.of("currentPassword", Fixtures.PASSWORD, "newPassword", NEW_PASSWORD)).expect(204);
        String resetLink = PeopleSteps.resetPassword(admin, member.accountId()).jsonText("resetLink");
        console().post("/api/public/password-reset/confirm")
                .json(Map.of("token", Fixtures.tokenOf(resetLink), "password", NEW_PASSWORD + "2")).expect(204);
        Session locked = fixtures.agent(admin);
        for (int i = 0; i < 5; i++) {
            console().post("/api/public/sign-in")
                    .json(Map.of("email", locked.email(), "password", "wrong-Password-" + i)).expect(401);
        }
        admin.client().patch(companyPath(company.code())).json(Map.of("name", Unique.text("Renamed company")))
                .expect(200);
        admin.client().put(companyPath(other.code()) + "/status").json(Map.of("status", "SUSPENDED")).expect(200);
        fixtures.signInAgain(admin);
        export(admin, "CSV", "actorAccountId", admin.accountId()).expectStatus(200);
        Set<Long> accounts = Set.of(admin.accountId(), customer.accountId(), invited.accountId(), agent.accountId(),
                member.accountId(), locked.accountId());

        JsonNode answer = assertValid(admin.client());

        List<JsonNode> mine = chain(admin).stream()
                .filter(line -> accounts.contains(line.path("actor").path("accountId").asLong(-1))
                        || accounts.contains(line.path("target").path("accountId").asLong(-1)))
                .toList();
        assertThat(mine.stream().map(line -> text(line, "action")).distinct().toList())
                .as("kinds of entry this test caused").containsAll(KINDS);
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer)
                .isGreaterThanOrEqualTo(mine.size());
    }

    @Test
    @DisplayName("S1B-AC-18 an agent who asks for the integrity check gets 403 FORBIDDEN")
    void agentCannotRunTheIntegrityCheck() {
        Session agent = fixtures.agent(fixtures.signInAdmin());

        ApiResponse answer = ChainSteps.verify(agent);

        assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isEqualTo(403);
        assertThat(answer.errorCode()).isEqualTo("FORBIDDEN");
    }
}
