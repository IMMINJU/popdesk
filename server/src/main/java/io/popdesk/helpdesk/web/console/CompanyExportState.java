package io.popdesk.helpdesk.web.console;

/** Where a company export stands (PII-8, S10B-AC-01, S10B-AC-03). */
public enum CompanyExportState {
    /** The ZIP is being written. One export runs at a time in the installation (S10B-AC-02). */
    RUNNING,
    /** The ZIP is ready to download, for 24 hours (S10B-AC-07). */
    DONE,
    /** Writing the ZIP stopped; {@code failure} says why (S10B-AC-05, S10B-AC-06). */
    FAILED,
    /** The ZIP was ready and is deleted: 24 hours passed, or it was gone after a restart (S10B-AC-08). */
    EXPIRED
}
