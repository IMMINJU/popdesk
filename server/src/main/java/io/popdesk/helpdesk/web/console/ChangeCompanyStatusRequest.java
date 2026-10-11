package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.CompanyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChangeCompanyStatusRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) CompanyStatus status) {}
