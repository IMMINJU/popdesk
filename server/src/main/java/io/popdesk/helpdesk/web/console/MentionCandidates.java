package io.popdesk.helpdesk.web.console;

import java.util.List;

/** Everyone the caller can mention in a comment on one issue (S4-AC-22). */
public record MentionCandidates(List<Person> items) {}
