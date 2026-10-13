package io.popdesk.helpdesk.attachment.storage;

/**
 * The storage gave no answer: the connection failed, was cut, or timed out. The next call would most
 * likely wait and fail the same way, so a cleanup stops its run on this one (S5B-AC-35).
 */
public class StorageUnreachableException extends StorageException {

    StorageUnreachableException(String message) {
        super(message);
    }
}
