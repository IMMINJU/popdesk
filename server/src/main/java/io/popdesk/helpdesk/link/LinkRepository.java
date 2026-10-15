package io.popdesk.helpdesk.link;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.LinkType;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
import io.popdesk.helpdesk.link.LinkRules.Link;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Links between two issues (table {@code issue_link}, LINK-1, LINK-2). The counts and the checks read this
 * table alone, without the viewer's scope (slice 6c design section 10); what a viewer reads joins the
 * other issue with {@link IssueScope}.
 */
@Repository
public class LinkRepository {

    /** Who made a link, as staff see them; {@code photoUpdatedAt} is null without a profile photo. */
    public record Person(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /** The other issue of a link, as it is now. */
    public record OtherIssue(UUID id, String key, String title, IssueStatus status, String companyCode,
            String companyName) {}

    /** A link of an issue as a viewer reads it. */
    public record LinkRow(long id, Link link, OtherIssue other, Person createdBy, Instant createdAt) {}

    /** A stored link with its ID. */
    record Stored(long id, Link link) {}

    private static final String LINK_COLUMNS = "l.id, l.low_issue_id, l.high_issue_id, l.type, l.duplicate_issue_id";

    private final JdbcClient jdbc;

    LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every link of the issue, whoever may see its other issue, in the order they were made. The caller
     * that judges a new link, or a restore (S7A-AC-24), holds the lock of the issue row, so no link of it
     * changes meanwhile.
     */
    public List<Link> linksOf(UUID issueId) {
        return jdbc.sql("select " + LINK_COLUMNS + " from issue_link l where l.low_issue_id = ? or l.high_issue_id = ? "
                        + "order by l.id")
                .params(issueId, issueId).query((rs, n) -> link(rs)).list();
    }

    /** The link between the two issues, if there is one. */
    Optional<Stored> between(UUID one, UUID other) {
        return jdbc.sql("select " + LINK_COLUMNS + " from issue_link l where (l.low_issue_id = ? and l.high_issue_id = ?) "
                        + "or (l.low_issue_id = ? and l.high_issue_id = ?)")
                .params(one, other, other, one).query((rs, n) -> new Stored(rs.getLong("id"), link(rs))).optional();
    }

    /**
     * The links of the issue whose other issue the viewer may see now, in the order they were made
     * (S6C-AC-12); with {@code linkId}, that link alone. The caller has seen that the viewer may see the
     * issue itself.
     */
    List<LinkRow> listVisible(Viewer viewer, UUID issueId, Long linkId) {
        Object[] params = concat(new Object[] {issueId, issueId, issueId}, IssueScope.params(viewer));
        return jdbc.sql("select " + LINK_COLUMNS + ", l.created_at, o.id as other_id, c.code, o.number, o.title, "
                        + "o.status, c.name, a.id as by_id, a.name as by_name, a.role as by_role, "
                        + "ap.updated_at as by_photo_updated_at "
                        + "from issue_link l "
                        + "join issue o on o.id = case when l.low_issue_id = ? then l.high_issue_id else l.low_issue_id end "
                        + "join company c on c.id = o.company_id "
                        + "join account a on a.id = l.created_by left join account_photo ap on ap.account_id = a.id "
                        + "where (l.low_issue_id = ? or l.high_issue_id = ?) and " + IssueScope.on("o")
                        + (linkId == null ? "" : " and l.id = ?") + " order by l.id")
                .params(linkId == null ? params : concat(params, linkId))
                .query(LinkRepository::row).list();
    }

    // Writes

    /** A new link, stored as {@link LinkRules#decide} gave it; returns its ID. */
    long insert(Link link, long createdBy, Instant now) {
        return jdbc.sql("insert into issue_link (tenant_id, low_issue_id, high_issue_id, type, duplicate_issue_id, "
                        + "created_by, created_at) values (1, ?, ?, ?, ?, ?, ?) returning id")
                .params(link.lowIssueId(), link.highIssueId(), link.type().name(), link.duplicateIssueId(), createdBy,
                        Timestamp.from(now))
                .query(Long.class).single();
    }

    void delete(long id) {
        jdbc.sql("delete from issue_link where id = ?").param(id).update();
    }

    private static Link link(ResultSet rs) throws SQLException {
        return new Link(rs.getObject("low_issue_id", UUID.class), rs.getObject("high_issue_id", UUID.class),
                LinkType.valueOf(rs.getString("type")), rs.getObject("duplicate_issue_id", UUID.class));
    }

    private static LinkRow row(ResultSet rs, int n) throws SQLException {
        Timestamp photo = rs.getTimestamp("by_photo_updated_at");
        return new LinkRow(rs.getLong("id"), link(rs),
                new OtherIssue(rs.getObject("other_id", UUID.class), rs.getString("code") + "-" + rs.getInt("number"),
                        rs.getString("title"), IssueStatus.valueOf(rs.getString("status")), rs.getString("code"),
                        rs.getString("name")),
                new Person(rs.getLong("by_id"), rs.getString("by_name"), Role.valueOf(rs.getString("by_role")),
                        photo == null ? null : photo.toInstant()),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Deletes every link of a purged issue (TRASH-3, S7A-AC-30), without an entry in the other issue's
     * history: its {@code LINK} entries name an issue nobody can see (slice 7a acceptance section 5, 10).
     * The caller holds the purged issue's row only; no link reaches an issue in the trash meanwhile.
     */
    public void deleteOfIssue(UUID issueId) {
        jdbc.sql("delete from issue_link where low_issue_id = ? or high_issue_id = ?").params(issueId, issueId)
                .update();
    }

}
