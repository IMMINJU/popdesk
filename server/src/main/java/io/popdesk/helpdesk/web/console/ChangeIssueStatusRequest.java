package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.IssueStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChangeIssueStatusRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) IssueStatus status) {}
