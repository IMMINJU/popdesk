package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashScene;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Restoring an issue the cleanup moved (S10B-AC-22, S10B-AC-31): the restore is the one of an issue an admin deleted
 * ({@code trash.IssueRestoreTest}), and the cleanup counts the 90 days again from the restore, so the next runs leave
 * the issue where it is although it was closed long before.
 */
class IssueRetentionRestoreTest extends RetentionAppTest {

    /**
     * The issue as staff see it, without what a delete and a restore change ({@code version}, {@code updatedAt}) and
     * what the 91 days change on their own: {@code reopenable} (14 days after closing) and {@code overdue} (the due
     * date passed).
     */
    private static JsonNode lasting(JsonNode issue) {
        ObjectNode copy = (ObjectNode) issue.deepCopy();
        copy.remove(List.of("version", "updatedAt", "reopenable", "overdue"));
        return copy;
    }

    @Test
    @DisplayName("S10B-AC-22, S10B-AC-31 restoring an issue the cleanup moved brings it back as it was, and the "
            + "cleanup moves it again only 90 days after the restore: not at the run a day later, at the run after "
            + "90 days")
    void restoredIssueCountsFromTheRestore() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CLOSED");
        String key = s.key();
        JsonNode detail = s.detail(flow);
        Instant closed = IssueFlow.time(detail, "closedAt");
        Map<String, Object> fields = rows.fields(key);
        Map<String, List<Map<String, Object>>> under = rows.under(key);

        runAt(closed.plus(PERIOD).plus(Duration.ofDays(1)));
        Session admin = fixtures.signInAgain(s.admin());
        JsonNode entry = TrashSteps.entry(admin, key);
        assertThat(entry).as("trash entry of %s after the run", key).isNotNull();
        assertThat(entry.path("deletedBy").isNull()).as("deletedBy of %s", entry).isTrue();

        Instant restoredAt = flow.tick();
        JsonNode restored = TrashSteps.restore(admin, key);

        JsonNode after = flow.detail(admin, key);
        assertThat(restored).as("the answer of the restore, against the issue read after it").isEqualTo(after);
        assertThat(lasting(after)).as("the issue after the restore, without version and updatedAt")
                .isEqualTo(lasting(detail));
        assertThat(rows.fields(key)).as("the issue's own fields after the restore").isEqualTo(fields);
        assertThat(rows.under(key)).as("what is under the issue after the restore").isEqualTo(under);

        runAt(restoredAt.plus(Duration.ofHours(25)));
        assertThat(TrashSteps.entry(fixtures.signInAgain(admin), key)).as("trash entry of %s a day after the restore",
                key).isNull();
        runAt(restoredAt.plus(PERIOD).minus(Duration.ofHours(1)));
        assertThat(TrashSteps.entry(fixtures.signInAgain(admin), key))
                .as("trash entry of %s an hour before 90 days after the restore", key).isNull();

        Instant again = restoredAt.plus(PERIOD).plus(Duration.ofHours(1));
        runAt(again);
        JsonNode second = TrashSteps.entry(fixtures.signInAgain(admin), key);
        assertThat(second).as("trash entry of %s an hour after 90 days after the restore", key).isNotNull();
        assertThat(second.path("deletedBy").isNull()).as("deletedBy of %s", second).isTrue();
        assertThat(IssueFlow.time(second, "deletedAt")).as("deletedAt of %s", second).isEqualTo(again);
    }
}
