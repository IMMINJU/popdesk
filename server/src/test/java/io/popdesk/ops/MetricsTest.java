package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.Prometheus.Names;
import io.popdesk.ops.Prometheus.Scrape;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Metrics on the management port (architecture section 6, OPS-2). The names are proposed in
 * {@link Prometheus.Names}; the criterion fixes what is measured, not the names.
 */
class MetricsTest extends BareAppTest {

    private static final Pattern EMAIL = Pattern.compile("[^\\s\"@,{}]+@[^\\s\"@,{}]+\\.[A-Za-z]{2,}");

    @Autowired
    Environment environment;

    private Prometheus prometheus;

    @BeforeEach
    void setUp() {
        prometheus = new Prometheus(environment);
    }

    @Test
    @DisplayName("S0-AC-78 requests are counted and timed per route")
    void requestCountAndLatency() {
        Session admin = fixtures.signInAdmin();
        Scrape before = prometheus.scrape();
        double count = before.sum(Names.REQUESTS_COUNT, s -> "/api/console/companies".equals(s.label("uri"))
                && "GET".equals(s.label("method")));

        for (int i = 0; i < 3; i++) {
            admin.client().get("/api/console/companies").expect(200);
        }

        Wait.untilAsserted("the request count", () -> {
            Scrape after = prometheus.scrape();
            assertThat(after.sum(Names.REQUESTS_COUNT, s -> "/api/console/companies".equals(s.label("uri"))
                    && "GET".equals(s.label("method")))).isGreaterThanOrEqualTo(count + 3);
            assertThat(after.named(Names.REQUESTS_SUM)).as(Names.REQUESTS_SUM).isNotEmpty();
        });
    }

    @Test
    @DisplayName("S0-AC-78 no metric label holds an email, an issue title, or a value from a request path")
    void labelsHoldNoPersonalData() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        String title = Unique.text("Metric title");
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).title(title));
        customer.client().get("/api/portal/issues/" + issue.key()).expect(200);
        console().post("/api/public/sign-in").json(Map.of("email", customer.email(), "password", "wrong-Password-1"))
                .expect(401);

        Scrape scrape = prometheus.scrape();

        List<String> labelValues = scrape.samples().stream().flatMap(s -> s.labels().values().stream()).toList();
        assertThat(labelValues).as("label values that look like an email")
                .noneMatch(v -> EMAIL.matcher(v).find());
        assertThat(scrape.text()).doesNotContain(title).doesNotContain(customer.email()).doesNotContain(issue.key());
    }

    @Test
    @DisplayName("S0-AC-78 each cleanup job has its last run time and deleted count")
    void cleanupJobs() {
        fixtures.signInAdmin();
        Instant due = clock.advance(Duration.ofHours(25));

        Wait.untilAsserted("every cleanup job ran after " + due, Duration.ofSeconds(90), () -> {
            Scrape scrape = prometheus.scrape();
            Set<String> ran = scrape.named(Names.CLEANUP_LAST_RUN).stream()
                    .filter(s -> s.value() >= due.getEpochSecond())
                    .map(s -> s.label("job"))
                    .collect(Collectors.toSet());
            assertThat(ran).as("jobs that ran after %s", due).hasSizeGreaterThanOrEqualTo(3);
            Set<String> counted = scrape.where(s -> s.name().startsWith(Names.CLEANUP_DELETED_PREFIX)).stream()
                    .map(s -> s.label("job"))
                    .collect(Collectors.toSet());
            assertThat(counted).as("jobs with a deleted count").containsAll(ran);
        });
    }

    @Test
    @DisplayName("S0-AC-78 the database size is measured")
    void databaseSize() {
        fixtures.signInAdmin();
        long actual = db.count("select pg_database_size(current_database())");

        double measured = prometheus.scrape().single(Names.DB_SIZE);

        assertThat(measured).as("%s against pg_database_size %s", Names.DB_SIZE, actual)
                .isBetween(actual / 2.0, actual * 2.0);
    }

    @Test
    @DisplayName("S0-AC-78 the attachment total grows by the size of an upload")
    void attachmentTotal() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        double before = prometheus.scrape().single(Names.ATTACHMENTS_SIZE);

        fixtures.attach(customer, issue.key(), new File("sized.bin", "application/octet-stream", new byte[12_345]));

        Wait.untilAsserted("the attachment total", () -> assertThat(prometheus.scrape().single(Names.ATTACHMENTS_SIZE))
                .isGreaterThanOrEqualTo(before + 12_345));
    }

    @Test
    @DisplayName("S0-AC-78 the free space of the data volume is measured")
    void volumeFreeSpace() {
        fixtures.signInAdmin();

        double free = prometheus.scrape().single(Names.VOLUME_FREE);

        assertThat(free).as(Names.VOLUME_FREE).isGreaterThan(0);
    }
}
