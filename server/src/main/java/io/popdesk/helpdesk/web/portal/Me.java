package io.popdesk.helpdesk.web.portal;

/** The signed-in customer. */
public record Me(long id, String email, String name) {}
