package io.popdesk.helpdesk.web.console;

import java.util.List;

/** Every label of one company, at most 200 (S6A-AC-10). */
public record CompanyLabels(List<Label> items) {}
