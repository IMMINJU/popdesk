package io.popdesk.helpdesk.web.console;

/** Why a company export is {@code FAILED} (S10B-AC-05, S10B-AC-06). */
public enum CompanyExportFailure {
    /** The app stopped or started again while the export ran. */
    INTERRUPTED,
    /** The attachment storage could not be read. */
    STORAGE,
    /** The export file could not be written, for example for lack of disk space. */
    DISK,
    /** Anything else. */
    UNEXPECTED
}
