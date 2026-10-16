package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 8a (S8A-AC-26,
 * ADR D-24): two policy versions with goals, business hours, and holidays, and issues under SLA whose clocks run, are
 * settled, and are paused in the trash. The dump is restored into a database of its own and read there, like
 * {@link TrashSampleTest}; the tables and columns are the ones the slice's design names ({@code design.md} 1절).
 */
@Tag("deploy")
class SlaSampleTest {

    private final String project = "pdt-sla-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S8A-AC-26 the sample of this version has two SLA policy versions with goals, business hours, and "
            + "holidays, and issues under SLA with a running clock, with a settled goal, and paused in the trash")
    void sampleHasPolicyVersionsAndIssuesUnderSla() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            String issue = schema + ".issue";
            assertThat(db.count("select count(*) from " + schema + ".sla_policy p"
                    + " where exists (select 1 from " + schema + ".sla_target t where t.version = p.version"
                    + "   and (t.first_response_minutes is not null or t.resolution_minutes is not null))"
                    + " and exists (select 1 from " + schema + ".sla_hours h where h.version = p.version)"
                    + " and exists (select 1 from " + schema + ".sla_holiday d where d.version = p.version)"))
                    .as("policy versions with goals, business hours, and holidays").isGreaterThanOrEqualTo(2);

            assertThat(db.count("select count(*) from " + issue + " where sla_version is not null "
                    + "and deleted_at is null and (fr_since is not null or res_since is not null) "
                    + "and sla_due_at is not null"))
                    .as("live issues under SLA with a running clock and a due time").isPositive();
            assertThat(db.count("select count(*) from " + issue + " where sla_version is not null "
                    + "and (fr_outcome in ('MET', 'MISSED') or res_outcome in ('MET', 'MISSED'))"))
                    .as("issues under SLA with a settled goal").isPositive();
            assertThat(db.count("select count(*) from " + issue + " where sla_version is not null "
                    + "and deleted_at is not null and purged_at is null and fr_since is null and res_since is null "
                    + "and (fr_outcome is null or res_outcome is null) and res_spent is not null"))
                    .as("issues under SLA paused in the trash").isPositive();
        });
    }
}
