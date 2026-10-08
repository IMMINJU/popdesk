package io.popdesk.helpdesk.web.console;

/** How an issue came in (D-34). Only `PORTAL` in the first slice. */
public enum IssueChannel {
    PORTAL,
    EMAIL,
    PHONE,
    OTHER
}
