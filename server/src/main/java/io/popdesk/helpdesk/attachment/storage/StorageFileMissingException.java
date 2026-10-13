package io.popdesk.helpdesk.attachment.storage;

/** A read found no contents where the attachment's row says they are (S5B-AC-31). */
public class StorageFileMissingException extends StorageException {

    StorageFileMissingException(String message) {
        super(message);
    }
}
