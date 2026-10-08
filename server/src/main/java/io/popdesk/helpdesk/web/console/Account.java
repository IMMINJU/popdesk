package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import java.time.OffsetDateTime;

/** A row of the account list (C-10). */
public record Account(long id, String email, String name, Role role, AccountStatus status, OffsetDateTime createdAt) {}
