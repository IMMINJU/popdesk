package io.popdesk.helpdesk.attachment.storage;

/**
 * An attachment storage failed or cannot be used. The message is written here, for the log and the
 * operator: it names setting keys, the storage kind, a status code, or an exception's class, and never
 * a key's value or what a client library said (S5B-AC-25). It has no cause for the same reason.
 */
public class StorageException extends RuntimeException {

    StorageException(String message) {
        super(message);
    }
}
