package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MB;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static io.popdesk.attachment.AttachmentSteps.uploadToComment;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The team quota ({@code POPDESK_ATTACHMENT_QUOTA}, here 1 MB) and its 80% warning.
 *
 * <p>The quota counts every attachment of the team, so this class has a context and a database of
 * its own, and its tests run in order on one growing total: 800,000 bytes (under 80%), then
 * 850,000 (over 80%), then exactly 1 MB (the quota), then one byte more.
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=1MB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AttachmentQuotaTest extends BareAppTest {

    private static final long QUOTA = MB;

    /** The admins, the customer, and the issue every step adds to. */
    private record Team(Scene scene, Session secondAdmin, Session agent, Issue issue) {}

    private static Team team;

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-58 under 80% of the quota an upload leaves no warning log and no alert")
    void underEightyPercent() {
        Scene s = steps.scene();
        Session secondAdmin = fixtures.admin(s.admin());
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        team = new Team(s, secondAdmin, agent, issue);

        AppLog.Mark mark = log.mark();
        ApiResponse created = upload(s.customer(), issue.key(), "first.bin", bytes(800_000, 1));

        assertThat(created.status()).isEqualTo(201);
        Wait.holds("no quota alert under 80%", Duration.ofSeconds(2),
                () -> quotaAlerts(s.admin()).isEmpty() && quotaAlerts(secondAdmin).isEmpty());
        assertThat(log.lines(mark)).as("WARN lines after the upload").noneMatch(l -> l.contains("WARN"));
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-58 an upload that takes the total over 80% of the quota leaves a warning log and an alert for every admin")
    void overEightyPercent() {
        Team t = team();
        AppLog.Mark mark = log.mark();

        ApiResponse created = upload(t.scene().customer(), t.issue().key(), "second.bin", bytes(50_000, 2));

        assertThat(created.status()).isEqualTo(201);
        log.awaitLine(mark, "WARN");
        Wait.untilAsserted("a quota alert in every admin's inbox", () -> {
            assertThat(quotaAlerts(t.scene().admin())).hasSize(1);
            assertThat(quotaAlerts(t.secondAdmin())).hasSize(1);
        });
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-58 uploads up to the quota are stored, internal ones counted; one byte more is 400 ATTACHMENT_QUOTA_EXCEEDED and not stored")
    void overQuota() {
        Team t = team();
        Comment internal = fixtures.internalComment(t.agent(), t.issue().key(), "Internal logs");
        long used = 850_000;

        ApiResponse toQuota = uploadToComment(t.agent(), t.issue().key(), internal.id(), "internal.bin",
                bytes((int) (QUOTA - used), 3));
        ApiResponse over = upload(t.scene().customer(), t.issue().key(), "over.bin", bytes(1, 4));

        assertThat(toQuota.status()).isEqualTo(201);
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.errorCode()).isEqualTo("ATTACHMENT_QUOTA_EXCEEDED");
        assertThat(list(t.agent(), t.issue().key())).extracting(n -> text(n, "fileName"))
                .containsExactlyInAnyOrder("first.bin", "second.bin", "internal.bin");
    }

    private static Team team() {
        if (team == null) {
            throw new AssertionError("The first step of this class did not set up the team");
        }
        return team;
    }

    private static List<JsonNode> quotaAlerts(Session admin) {
        ApiResponse page = admin.client().get("/api/console/notifications").query("size", 100).expect(200);
        return page.json().path("items").values().stream()
                .filter(n -> "ATTACHMENT_QUOTA_WARNING".equals(text(n, "kind")))
                .toList();
    }
}
