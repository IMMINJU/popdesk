package io.popdesk.deploy;

import static io.popdesk.deploy.IssueInstallSteps.keptSample;
import static io.popdesk.deploy.IssueInstallSteps.sampleAdmin;
import static io.popdesk.deploy.IssueInstallSteps.startedOn;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gram index of slice b8 through {@code popdesk backup} and {@code popdesk restore} (B8-AC-07, like
 * {@link BackupRestoreTest}): the image under test is started on the 0.5.0 sample, so its migration made the index;
 * a backup of that is restored into an empty database by a new container of the app service, and the app is started
 * on the restored database.
 */
@Tag("deploy")
class ShortSearchRestoreTest {

    @Test
    @DisplayName("B8-AC-07 popdesk restore of a backup builds the gram index again, valid, and the restored "
            + "installation finds with a word of one or two letters what 0.5.0 found")
    void restoreBuildsTheGramIndex() {
        try (Stack stack = startedOn("gramsrestore", keptSample("0.5.0", "sample.dump"))) {
            Db db = stack.db();
            ShortSearchSteps.assertGramIndexValid(db, "before the backup");
            Path dump = IssueInstallSteps.backup(stack);
            stack.command("stop", "app").expectOk("docker compose stop app");
            IssueInstallSteps.emptyDatabase(stack, db);

            Cli.Result restore = stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                    Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore", "/tmp/restore.dump");

            assertThat(restore.exit()).as("popdesk restore into an empty database\n%s", restore).isZero();
            ShortSearchSteps.assertGramIndexValid(db, "after the restore");
            stack.up("app");
            stack.awaitHealthy("app");
            ShortSearchSteps.assertShortSearchAsBefore(stack, sampleAdmin(stack), db);
        }
    }
}
