package io.popdesk.helpdesk;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Reading a due date as it was sent, and judging it against today (DUE-1). The due date of an issue
 * (slice 6a) and of a task (TASK-6) use this one reading, so the two cannot drift apart (slice 6b design
 * section 0, 5). Pure: no clock and no I/O; the caller gives "today" in the installation's time zone.
 */
public final class DueDates {

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /**
     * What {@link #read} made of a text: the date, or the error code of the field. Exactly one of the two
     * is not null.
     *
     * @param date the date, when the text is one a due date can be
     * @param error {@code REQUIRED}, {@code INVALID_FORMAT}, or {@code OUT_OF_RANGE}, when it is not
     */
    public record Read(LocalDate date, String error) {}

    private DueDates() {}

    /**
     * The due date written as {@code text}, read strictly (S6A-AC-31, S6B-AC-05).
     *
     * @param text the value as sent; may be null
     * @param today today in the installation's time zone
     * @return {@code REQUIRED} for null and the empty text; {@code INVALID_FORMAT} for anything but
     *     {@code YYYY-MM-DD} with ASCII digits of a day that exists: whitespace around it, a time or an
     *     offset after it ({@code 2026-10-15T00:00}), a number of another length ({@code 20261015}), or a
     *     day no month has ({@code 2026-02-30}); {@code OUT_OF_RANGE} for a date before {@code today},
     *     also the date something has already (S6A-AC-35); else the date, {@code today} itself included
     */
    public static Read read(String text, LocalDate today) {
        if (text == null || text.isEmpty()) {
            return new Read(null, "REQUIRED");
        }
        if (!DATE.matcher(text).matches()) {
            return new Read(null, "INVALID_FORMAT");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return new Read(null, "INVALID_FORMAT");
        }
        return date.isBefore(today) ? new Read(null, "OUT_OF_RANGE") : new Read(date, null);
    }

    /**
     * Whether a due date has passed (S6A-AC-35).
     *
     * @param dueDate the due date; null when there is none
     * @param today today in the installation's time zone
     * @return true when {@code dueDate} is before {@code today}; false for null, for today, and later
     */
    public static boolean passed(LocalDate dueDate, LocalDate today) {
        return dueDate != null && dueDate.isBefore(today);
    }
}
