package io.popdesk.helpdesk.web.console;

import java.util.List;

/** Everyone who can be the assignee of an issue of one company (S3-AC-27). */
public record AssigneeCandidates(List<Person> items) {}
