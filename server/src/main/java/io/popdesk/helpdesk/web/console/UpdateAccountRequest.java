package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A field left out keeps its value (ADR D-44). */
public record UpdateAccountRequest(@Schema(description = "1 to 50 characters.") String name) {}
