package io.popdesk.helpdesk.web.console;

/** The customer manager who made a membership request. */
public record MembershipRequester(long accountId, String name, String email) {}
