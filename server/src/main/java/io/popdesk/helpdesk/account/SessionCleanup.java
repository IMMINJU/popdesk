package io.popdesk.helpdesk.account;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * Deletes sessions that ended: 8 hours after sign-in by the app clock (AUTH-4, S0-AC-82). Such a session
 * already lets nobody in ({@link SessionFilter}); this removes its rows. Spring Session's own cleanup
 * still removes sessions idle past their timeout.
 */
@Component
class SessionCleanup implements CleanupJob {

    static final String NAME = "session-cleanup";

    private final SessionTableRepository table;
    private final SessionRepository<? extends Session> sessions;

    SessionCleanup(SessionTableRepository table, SessionRepository<? extends Session> sessions) {
        this.table = table;
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public long run(Instant now) {
        long deleted = 0;
        for (String id : table.listSessionIds()) {
            Session session = sessions.findById(id);
            SignedIn signedIn = session == null ? null : session.getAttribute(SignedIn.ATTRIBUTE);
            if (signedIn != null && !now.isBefore(signedIn.signedInAt().plus(Sessions.LIFETIME))) {
                sessions.deleteById(id);
                deleted++;
            }
        }
        return deleted;
    }
}
