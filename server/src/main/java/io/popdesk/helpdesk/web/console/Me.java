package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;

/** The signed-in agent or admin. */
public record Me(long id, String email, String name, Role role) {}
