package io.popdesk.notification.web.console;

/**
 * Why a round of receiving email failed (S12-AC-10, S12-AC-07): the IMAP server could not be reached or timed out,
 * it refused the sign-in, the inbox folder is not there, or it can neither move a message nor expunge one alone.
 */
public enum MailInFailureKind {
    CONNECT,
    AUTH,
    FOLDER,
    UNSUPPORTED
}
