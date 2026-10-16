package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.sla.BusinessCalendar.Span;
import io.popdesk.helpdesk.sla.SlaVersion.Changer;
import io.popdesk.helpdesk.sla.SlaVersion.Goals;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The versions of the SLA policy (tables {@code sla_policy}, {@code sla_target}, {@code sla_hours},
 * {@code sla_holiday}). A version's rows are written once and never changed; only the time zone of version 1 is
 * filled in once (design.md 4절).
 */
@Repository
class SlaPolicyRepository {

    /** The current version's row, the newest one, locked as {@code lock} says. */
    private static final String CURRENT = "select version from sla_policy "
            + "where version = (select max(version) from sla_policy) for ";

    private final JdbcClient jdbc;

    SlaPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The current version's number, not locked. */
    int current() {
        return jdbc.sql("select max(version) from sla_policy").query(Integer.class).single();
    }

    /**
     * Locks the current version's row until the end of the transaction and gives its number (design.md 0절 5, 8,
     * 8b). A statement that waited for the lock still gives the row it first picked, which a policy change that
     * held it may have made old: so the current version is read again, and while it is another one, that one is
     * locked too.
     *
     * @param mode {@code key share} for a write of clocks, {@code update} for a new version
     */
    int lockCurrent(String mode) {
        int locked = jdbc.sql(CURRENT + mode).query(Integer.class).single();
        while (true) {
            int now = current();
            if (now == locked) {
                return now;
            }
            locked = jdbc.sql("select version from sla_policy where version = ? for " + mode).param(now)
                    .query(Integer.class).single();
        }
    }

    /**
     * The version with this number, if there is one. {@code installationZone} stands in for a time zone not filled
     * in.
     */
    Optional<SlaVersion> find(int version, ZoneId installationZone) {
        return jdbc.sql("select p.version, p.time_zone, p.starts_at, p.changed_at, p.changed_by, a.name, a.role, "
                        + "ap.updated_at as photo_updated_at from sla_policy p "
                        + "left join account a on a.id = p.changed_by "
                        + "left join account_photo ap on ap.account_id = p.changed_by where p.version = ?")
                .param(version)
                .query((rs, n) -> {
                    String zone = rs.getString("time_zone");
                    long changedBy = rs.getLong("changed_by");
                    Changer changer = null;
                    if (!rs.wasNull()) {
                        Timestamp photo = rs.getTimestamp("photo_updated_at");
                        changer = new Changer(changedBy, rs.getString("name"), Role.valueOf(rs.getString("role")),
                                photo == null ? null : photo.toInstant());
                    }
                    return new SlaVersion(version, zone == null ? installationZone : ZoneId.of(zone),
                            rs.getTimestamp("starts_at").toInstant(), changer,
                            rs.getTimestamp("changed_at").toInstant(), targets(version), hours(version),
                            holidays(version));
                }).optional();
    }

    private Map<Priority, Goals> targets(int version) {
        Map<Priority, Goals> out = new EnumMap<>(Priority.class);
        jdbc.sql("select priority, first_response_minutes, resolution_minutes from sla_target where version = ?")
                .param(version)
                .query(rs -> {
                    out.put(Priority.valueOf(rs.getString("priority")),
                            new Goals(rs.getObject("first_response_minutes", Integer.class),
                                    rs.getObject("resolution_minutes", Integer.class)));
                });
        return out;
    }

    private Map<DayOfWeek, Span> hours(int version) {
        Map<DayOfWeek, Span> out = new EnumMap<>(DayOfWeek.class);
        // As text: a time of 24:00:00 has no LocalTime.
        jdbc.sql("select weekday, cast(opens as text) as opens, cast(closes as text) as closes from sla_hours "
                        + "where version = ?")
                .param(version)
                .query(rs -> {
                    out.put(DayOfWeek.of(rs.getInt("weekday")),
                            new Span(minutes(rs.getString("opens")), minutes(rs.getString("closes"))));
                });
        return out;
    }

    private List<LocalDate> holidays(int version) {
        return jdbc.sql("select day from sla_holiday where version = ? order by day").param(version)
                .query((rs, n) -> rs.getObject("day", LocalDate.class)).list();
    }

    /** Minutes of the day of a time as the database writes it, {@code HH:MM:SS}. */
    private static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    /** {@code HH:MM:00} of minutes of the day, {@code 24:00:00} for the end of the day. */
    private static String time(int minutes) {
        return String.format("%02d:%02d:00", minutes / 60, minutes % 60);
    }

    /**
     * A new version with all its rows. The caller holds the current version's row {@code FOR UPDATE} and makes
     * {@code version} the next number.
     *
     * @param changedBy the admin, or null for popdesk
     */
    void insert(int version, ZoneId zone, Instant startsAt, Long changedBy, Instant changedAt,
            Map<Priority, Goals> targets, Map<DayOfWeek, Span> hours, List<LocalDate> holidays) {
        jdbc.sql("insert into sla_policy (version, tenant_id, time_zone, starts_at, changed_by, changed_at) "
                        + "values (?, 1, ?, ?, ?, ?)")
                .params(version, zone.getId(), Timestamp.from(startsAt), changedBy, Timestamp.from(changedAt))
                .update();
        for (Priority priority : Priority.values()) {
            Goals goals = targets.getOrDefault(priority, Goals.NONE);
            jdbc.sql("insert into sla_target (version, tenant_id, priority, first_response_minutes, "
                            + "resolution_minutes) values (?, 1, ?, ?, ?)")
                    .params(version, priority.name(), goals.firstResponse(), goals.resolution())
                    .update();
        }
        hours.forEach((day, span) -> jdbc.sql("insert into sla_hours (version, tenant_id, weekday, opens, closes) "
                        + "values (?, 1, ?, cast(? as time), cast(? as time))")
                .params(version, day.getValue(), time(span.opens()), time(span.closes()))
                .update());
        if (!holidays.isEmpty()) {
            jdbc.sql("insert into sla_holiday (version, tenant_id, day) "
                            + "select ?, 1, d from unnest(cast(? as date[])) as d")
                    .params(version, holidays.stream().map(LocalDate::toString).toArray(String[]::new))
                    .update();
        }
    }

    /** Fills in the time zone of version 1 once (S8A-AC-07); a zone filled in before stays. */
    void fillFirstZone(ZoneId zone) {
        jdbc.sql("update sla_policy set time_zone = ? where version = 1 and time_zone is null").param(zone.getId())
                .update();
    }

    /** The time zone stored with a version, null for version 1 before it was filled in. */
    String storedZone(int version) {
        return jdbc.sql("select time_zone from sla_policy where version = ?").param(version).query(String.class)
                .single();
    }
}
