package io.popdesk.helpdesk.web.console;

/** The admin who asked for a company export, by the account's current name. */
public record CompanyExportRequester(long accountId, String name) {}
