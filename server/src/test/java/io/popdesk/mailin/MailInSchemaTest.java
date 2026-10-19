package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two tables of migration V25 (S12-AC-40, {@code design.md} 1절), read from the database: the received email
 * record has no HTTP view, so this test reads the schema itself (the exception the slice allows for a migration).
 * The rules of every business table ({@code tenant_id}, time columns with a zone) are the tests of {@code start}
 * ({@code TenantIdTest}, {@code TimeTest}), which read every table of the schema and so these two too.
 */
class MailInSchemaTest extends BareAppTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /** Column name to "type NULL" or "type NOT NULL", in the order of the table. */
    private Map<String, String> shape(String table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Column column : db.columns(table)) {
            out.put(column.name(), column.dataType() + (column.nullable() ? " NULL" : " NOT NULL"));
        }
        return out;
    }

    private static Map<String, String> columns(String... nameThenShape) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < nameThenShape.length; i += 2) {
            out.put(nameThenShape[i], nameThenShape[i + 1]);
        }
        return out;
    }

    /** The quoted values of a check constraint's definition. */
    private static Set<String> quoted(String definition) {
        Set<String> out = new TreeSet<>();
        Matcher m = QUOTED.matcher(definition);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The definitions of the foreign keys on {@code column}. */
    private static List<String> foreignKeys(Map<String, String> constraints, String column) {
        return constraints.values().stream().filter(d -> d.startsWith("FOREIGN KEY (" + column + ")")).toList();
    }

    @Test
    @DisplayName("S12-AC-40 V25 ran, and mail_received has its thirteen columns with tenant_id bigint NOT NULL, the "
            + "unique mail_received_message_key_key, the checks on key_source, the six outcomes, outcome and "
            + "decided_at null together, and attempts >= 0, and foreign keys on issue_id (ON DELETE SET NULL "
            + "DEFERRABLE INITIALLY DEFERRED) and comment_id (ON DELETE SET NULL)")
    void mailReceivedTable() {
        assertThat(db.count("select count(*) from \"" + db.schema() + "\".flyway_schema_history "
                + "where version = '25' and success"))
                .as("V25 in flyway_schema_history").isEqualTo(1);

        assertThat(shape("mail_received")).as("columns of mail_received").isEqualTo(columns(
                "id", "bigint NOT NULL",
                "tenant_id", "bigint NOT NULL",
                "message_key", "text NOT NULL",
                "key_source", "text NOT NULL",
                "sender_account_id", "bigint NULL",
                "sender_address", "text NULL",
                "attempts", "integer NOT NULL",
                "outcome", "text NULL",
                "reason", "text NULL",
                "issue_id", "uuid NULL",
                "comment_id", "bigint NULL",
                "first_seen_at", "timestamp with time zone NOT NULL",
                "decided_at", "timestamp with time zone NULL"));

        Map<String, String> constraints = db.constraints("mail_received");
        assertThat(constraints).as("constraints of mail_received")
                .containsEntry("mail_received_message_key_key", "UNIQUE (message_key)")
                .containsKeys("mail_received_key_source_check", "mail_received_outcome_check",
                        "mail_received_decided_check", "mail_received_attempts_check");
        String keySource = constraints.get("mail_received_key_source_check");
        assertThat(keySource).as("mail_received_key_source_check").startsWith("CHECK").contains("key_source");
        assertThat(quoted(keySource)).as("values of %s", keySource)
                .containsExactlyInAnyOrder("MESSAGE_ID", "CONTENT");
        String outcome = constraints.get("mail_received_outcome_check");
        assertThat(outcome).as("mail_received_outcome_check").startsWith("CHECK").contains("outcome");
        assertThat(quoted(outcome)).as("values of %s", outcome).containsExactlyInAnyOrder(
                "ISSUE", "COMMENT", "NOTICE", "DISCARDED", "QUARANTINED", "RATE_LIMITED");
        assertThat(constraints.get("mail_received_decided_check")).as("mail_received_decided_check")
                .startsWith("CHECK").contains("(outcome IS NULL) = (decided_at IS NULL)");
        assertThat(constraints.get("mail_received_attempts_check")).as("mail_received_attempts_check")
                .startsWith("CHECK").contains("attempts >= 0");

        assertThat(foreignKeys(constraints, "issue_id")).as("foreign key of mail_received.issue_id in %s", constraints)
                .singleElement().satisfies(d -> assertThat(d).startsWith("FOREIGN KEY (issue_id) REFERENCES issue(id)")
                        .contains("ON DELETE SET NULL").contains("DEFERRABLE INITIALLY DEFERRED"));
        assertThat(foreignKeys(constraints, "comment_id"))
                .as("foreign key of mail_received.comment_id in %s", constraints)
                .singleElement().satisfies(d -> assertThat(d)
                        .startsWith("FOREIGN KEY (comment_id) REFERENCES comment(id)").contains("ON DELETE SET NULL"));
        assertThat(foreignKeys(constraints, "sender_account_id"))
                .as("foreign key of mail_received.sender_account_id in %s", constraints)
                .containsExactly("FOREIGN KEY (sender_account_id) REFERENCES account(id)");

        Map<String, String> indexes = db.indexes("mail_received");
        assertThat(indexes).as("indexes of mail_received")
                .containsKeys("mail_received_first_seen_at_idx", "mail_received_sender_account_id_idx");
        assertThat(indexes.get("mail_received_first_seen_at_idx")).as("mail_received_first_seen_at_idx")
                .endsWith("(first_seen_at)");
        assertThat(indexes.get("mail_received_sender_account_id_idx")).as("mail_received_sender_account_id_idx")
                .endsWith("(sender_account_id)");
    }

    @Test
    @DisplayName("S12-AC-40 mail_sent has its five columns with tenant_id bigint NOT NULL, the unique "
            + "mail_sent_message_id_key, and no foreign key")
    void mailSentTable() {
        assertThat(shape("mail_sent")).as("columns of mail_sent").isEqualTo(columns(
                "id", "bigint NOT NULL",
                "tenant_id", "bigint NOT NULL",
                "message_id", "text NOT NULL",
                "issue_id", "uuid NOT NULL",
                "sent_at", "timestamp with time zone NOT NULL"));

        Map<String, String> constraints = db.constraints("mail_sent");
        assertThat(constraints).as("constraints of mail_sent")
                .containsEntry("mail_sent_message_id_key", "UNIQUE (message_id)");
        assertThat(constraints.values()).as("foreign keys of mail_sent")
                .noneMatch(d -> d.startsWith("FOREIGN KEY"));

        Map<String, String> indexes = db.indexes("mail_sent");
        assertThat(indexes).as("indexes of mail_sent").containsKeys("mail_sent_sent_at_idx", "mail_sent_issue_id_idx");
        assertThat(indexes.get("mail_sent_sent_at_idx")).as("mail_sent_sent_at_idx").endsWith("(sent_at)");
        assertThat(indexes.get("mail_sent_issue_id_idx")).as("mail_sent_issue_id_idx").endsWith("(issue_id)");
    }
}
