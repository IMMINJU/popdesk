package io.popdesk.helpdesk.label;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Labels of customer companies and labels on issues (tables {@code label} and {@code issue_label}, LBL-1
 * to LBL-3). A label is always read with the company it belongs to, never by its ID alone (S6A-AC-12,
 * S6A-AC-22): the caller found the company or the issue in the viewer's scope first.
 *
 * <p>Labels come in name order whatever the case: by the stored lower-case key, compared byte by byte
 * ({@code COLLATE "C"}) so the order does not depend on the database's collation, then by ID.
 */
@Repository
public class LabelRepository {

    /** A label with the number of issues that have it now (S6A-AC-10). */
    public record LabelRow(long id, String name, String color, long issueCount) {}

    /** A label as an issue names it: its current name and color (S6A-AC-05). */
    public record IssueLabel(long id, String name, String color) {}

    private static final String NAME_ORDER = " order by l.name_key collate \"C\", l.id";

    private final JdbcClient jdbc;

    LabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every label of the company, in name order, each with its issue count. */
    public List<LabelRow> list(long companyId) {
        return jdbc.sql("select l.id, l.name, l.color, (select count(*) from issue_label il "
                        + "join issue i on i.id = il.issue_id where il.label_id = l.id and i.deleted_at is null) "
                        + "as issue_count from label l where l.company_id = ?" + NAME_ORDER)
                .param(companyId)
                .query((rs, n) -> new LabelRow(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                        rs.getLong("issue_count")))
                .list();
    }

    /** The label of the company with this ID, with its issue count. */
    public Optional<LabelRow> find(long companyId, long id) {
        return jdbc.sql("select l.id, l.name, l.color, (select count(*) from issue_label il "
                        + "join issue i on i.id = il.issue_id where il.label_id = l.id and i.deleted_at is null) "
                        + "as issue_count from label l where l.company_id = ? and l.id = ?")
                .params(companyId, id)
                .query((rs, n) -> new LabelRow(rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                        rs.getLong("issue_count")))
                .optional();
    }

    /** How many labels the company has. The caller holds the company row, so no other creation counts at once. */
    public long count(long companyId) {
        return jdbc.sql("select count(*) from label where company_id = ?").param(companyId).query(Long.class).single();
    }

    /**
     * The labels of each of these issues, in name order; an issue without labels is not in the map. One
     * statement for a whole page (slice 6a design section 4).
     */
    public Map<UUID, List<IssueLabel>> listOnIssues(Collection<UUID> issueIds) {
        Map<UUID, List<IssueLabel>> labels = new LinkedHashMap<>();
        if (issueIds.isEmpty()) {
            return labels;
        }
        jdbc.sql("select il.issue_id, l.id, l.name, l.color from issue_label il join label l on l.id = il.label_id "
                        + "where il.issue_id = any(?)" + NAME_ORDER)
                .param(issueIds.toArray(UUID[]::new))
                .query(rs -> {
                    labels.computeIfAbsent(rs.getObject("issue_id", UUID.class), k -> new ArrayList<>())
                            .add(new IssueLabel(rs.getLong("id"), rs.getString("name"), rs.getString("color")));
                });
        return labels;
    }

    /** How many labels the issue has. The caller holds the issue row. */
    public long countOnIssue(UUID issueId) {
        return jdbc.sql("select count(*) from issue_label where issue_id = ?").param(issueId).query(Long.class)
                .single();
    }

    /** Whether the issue has the label. */
    public boolean onIssue(UUID issueId, long labelId) {
        return jdbc.sql("select count(*) from issue_label where issue_id = ? and label_id = ?")
                .params(issueId, labelId).query(Long.class).single() > 0;
    }

    // Writes

    /**
     * The name of the company's label with this ID, the row locked {@code FOR KEY SHARE} until the end of
     * the transaction: a deletion of the label waits until then, and a deletion that came first leaves
     * nothing to find. Changing the label's name or color does not wait. Empty when the company has no
     * such label, also when the ID is another company's label (S6A-AC-22).
     */
    public Optional<String> lockName(long companyId, long id) {
        return jdbc.sql("select name from label where company_id = ? and id = ? for key share")
                .params(companyId, id).query(String.class).optional();
    }

    /** A new label of the company; gives its ID. A name the company has, whatever the case, breaks the unique key. */
    public long insert(long companyId, LabelName name, String color, Instant now) {
        return jdbc.sql("insert into label (tenant_id, company_id, name, name_key, color, created_at, updated_at) "
                        + "values (1, ?, ?, ?, ?, ?, ?) returning id")
                .params(companyId, name.name(), name.key(), color, Timestamp.from(now), Timestamp.from(now))
                .query(Long.class).single();
    }

    /**
     * Stores the name and the color of the company's label. Another label of the company with the name,
     * whatever the case, breaks the unique key.
     *
     * @return whether the company has the label
     */
    public boolean update(long companyId, long id, LabelName name, String color, Instant now) {
        return jdbc.sql("update label set name = ?, name_key = ?, color = ?, updated_at = ? "
                        + "where company_id = ? and id = ?")
                .params(name.name(), name.key(), color, Timestamp.from(now), companyId, id)
                .update() == 1;
    }

    /**
     * Deletes the company's label, which takes it off every issue in the same statement (LBL-3, the
     * foreign key's {@code ON DELETE CASCADE}).
     *
     * @return whether the company had the label
     */
    public boolean delete(long companyId, long id) {
        return jdbc.sql("delete from label where company_id = ? and id = ?").params(companyId, id).update() == 1;
    }

    /**
     * Puts the company's label on the issue (slice 6a design section 0, 4): the company is a condition
     * of the statement that inserts, so a label of another company is never put on the issue.
     *
     * @return whether a row was inserted: false when the issue has the label already, or the company has
     *     no such label
     */
    public boolean insertOnIssue(UUID issueId, long companyId, long labelId, Instant now) {
        return jdbc.sql("insert into issue_label (issue_id, label_id, tenant_id, created_at) "
                        + "select ?, l.id, 1, ? from label l where l.id = ? and l.company_id = ? "
                        + "on conflict do nothing")
                .params(issueId, Timestamp.from(now), labelId, companyId)
                .update() == 1;
    }

    /**
     * Takes the label off the issue.
     *
     * @return the label's name now, for the history; empty when the issue did not have it
     */
    public Optional<String> deleteFromIssue(UUID issueId, long labelId) {
        return jdbc.sql("delete from issue_label il using label l where il.issue_id = ? and il.label_id = ? "
                        + "and l.id = il.label_id returning l.name")
                .params(issueId, labelId).query(String.class).optional();
    }

    /** Takes every label off a purged issue (TRASH-3, S7A-AC-30); the labels stay. The caller holds the issue row. */
    public void deleteOfIssue(UUID issueId) {
        jdbc.sql("delete from issue_label where issue_id = ?").param(issueId).update();
    }
}
