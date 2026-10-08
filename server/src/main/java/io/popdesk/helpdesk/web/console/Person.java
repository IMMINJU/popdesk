package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Role;

/** Who wrote, reported, or uploaded something, as staff see it. */
public record Person(long accountId, String name, Role role) {}
