package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 8b (S8B-AC-27,
 * {@code design.md} 6절): issues under SLA whose alerts were sent (the alerted marks filled), a closed summary row
 * of a recalculation, and the {@code SLA_RECALCULATED} item with its counts. The dump is restored into a database of
 * its own and read there, like {@link SlaSampleTest}; the tables and columns are the ones of {@code design.md} 1절.
 */
@Tag("deploy")
class SlaAlertSampleTest {

    private final String project = "pdt-sla-alert-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S8B-AC-27 the sample of this version has issues with an alerted mark, a closed recalculation "
            + "summary with a count above 0, and an SLA_RECALCULATED item that carries the counts")
    void sampleHasAlertedIssuesAndASummary() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            assertThat(db.count("select count(*) from " + schema + ".issue where sla_version is not null "
                    + "and (fr_alerted in ('WARNING', 'BREACHED') or res_alerted in ('WARNING', 'BREACHED'))"))
                    .as("issues under SLA with an alerted mark").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".issue where fr_settled_at is not null "
                    + "and fr_outcome in ('MET', 'MISSED')"))
                    .as("issues with a settled first response and its time").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".sla_recalc_report where closed_at is not null "
                    + "and became_breached + became_warning + released > 0"))
                    .as("closed recalculation summaries with a count").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".notification where kind = 'SLA_RECALCULATED' "
                    + "and issue_id is null and sla_became_breached is not null and sla_became_warning is not null "
                    + "and sla_released is not null "
                    + "and sla_became_breached + sla_became_warning + sla_released > 0"))
                    .as("SLA_RECALCULATED items with their counts").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".notification where kind in "
                    + "('SLA_RESPONSE_WARNING', 'SLA_RESPONSE_BREACHED', 'SLA_RESOLUTION_WARNING', "
                    + "'SLA_RESOLUTION_BREACHED') and issue_id is not null"))
                    .as("SLA alert items about an issue").isPositive();
        });
    }
}
