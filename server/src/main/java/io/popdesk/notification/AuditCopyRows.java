package io.popdesk.notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The send-queue rows of the audit log's outside copy (AUDIT-9, S1B-AC-30), written on a plain JDBC
 * connection: helpdesk puts them in while it appends the entries to the hash chain, in the same
 * transaction, from the app and from the commands in the container alike. A row holds the entry's ID
 * only; the body is read when it is sent ({@link AuditEntries}).
 */
public final class AuditCopyRows {

    /** The channel of the copy in {@code outbox} and {@code delivery_status}. */
    public static final String CHANNEL = "AUDIT_FORWARD";

    public static final String URL_KEY = "AUDIT_FORWARD_URL";
    public static final String TOKEN_KEY = "AUDIT_FORWARD_TOKEN";
    public static final String SANDBOX_KEY = "POPDESK_SANDBOX";

    /** The one kind of row this channel has. */
    static final String KIND = "AUDIT_ENTRY";

    private AuditCopyRows() {}

    /**
     * Whether the copy is on: {@code AUDIT_FORWARD_URL} has a value and {@code POPDESK_SANDBOX} is not
     * {@code true} (D-46: a copy of a real installation must not post to the real receiver).
     *
     * @param setting reads a setting by its key; null when it is not set
     */
    public static boolean enabled(UnaryOperator<String> setting) {
        String url = setting.apply(URL_KEY);
        String sandbox = setting.apply(SANDBOX_KEY);
        return url != null && !url.isBlank() && (sandbox == null || !sandbox.strip().equalsIgnoreCase("true"));
    }

    /**
     * Queues one copy per audit entry in the caller's transaction, due at once.
     *
     * @param due the app time the rows are queued at; their first attempt is then
     */
    public static void insert(Connection c, List<Long> auditIds, Instant due) throws SQLException {
        if (auditIds.isEmpty()) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement("insert into outbox (tenant_id, channel, kind, audit_id, state, "
                + "attempts, next_attempt_at, created_at) select 1, ?, ?, u.id, 'queued', 0, ?, ? "
                + "from unnest(?::bigint[]) with ordinality as u(id, n) order by u.n")) {
            s.setString(1, CHANNEL);
            s.setString(2, KIND);
            s.setObject(3, due.atOffset(ZoneOffset.UTC));
            s.setObject(4, due.atOffset(ZoneOffset.UTC));
            s.setArray(5, c.createArrayOf("bigint", auditIds.toArray(Long[]::new)));
            s.executeUpdate();
        }
    }
}
