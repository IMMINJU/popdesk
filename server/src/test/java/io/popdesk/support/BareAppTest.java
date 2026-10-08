package io.popdesk.support;

/**
 * Setup "bare": no SMTP and no webhook, as a first trial install. Invitation responses carry the
 * link; the app writes a log line in place of each mail.
 */
public abstract class BareAppTest extends AppTest {

    @Override
    protected final boolean smtpOn() {
        return false;
    }
}
