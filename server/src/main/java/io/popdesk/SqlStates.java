package io.popdesk;

import java.sql.SQLException;
import java.util.Optional;

/** Reads the SQL state of a database error from its cause chain, without its message. */
public final class SqlStates {

    /** {@code 55P03}: the lock did not come within {@code lock_timeout}. */
    public static final String LOCK_NOT_AVAILABLE = "55P03";

    /** {@code 40P01}: the transaction lost a deadlock. */
    public static final String DEADLOCK_DETECTED = "40P01";

    private SqlStates() {}

    /** The SQL state of the first {@link SQLException} in the cause chain that has one. */
    public static Optional<String> of(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }

    /** Whether retrying the whole transaction may work: a lock timeout or a deadlock (conventions). */
    public static boolean retryable(Throwable e) {
        return of(e).filter(s -> s.equals(LOCK_NOT_AVAILABLE) || s.equals(DEADLOCK_DETECTED)).isPresent();
    }
}
