package io.popdesk.helpdesk.web.console;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Issues")
@RestController
@RequestMapping("/api/console/issues")
class ConsoleIssueController {

    private final IssueService issues;

    ConsoleIssueController(IssueService issues) {
        this.issues = issues;
    }

    static Issue issue(IssueRow row) {
        return new Issue(row.key(), row.companyCode(), row.companyName(), row.title(), row.content(), row.status(),
                row.priority(), row.privateIssue(), new Person(row.reporterId(), row.reporterName(), row.reporterRole()),
                IssueChannel.valueOf(row.channel()), utc(row.firstResponseAt()), row.version(), utc(row.createdAt()),
                utc(row.updatedAt()));
    }

    static IssueSummary summary(IssueRow row) {
        return new IssueSummary(row.key(), row.title(), row.companyCode(), row.companyName(), row.status(),
                row.priority(), row.privateIssue(), new Person(row.reporterId(), row.reporterName(), row.reporterRole()),
                utc(row.createdAt()));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    @Operation(operationId = "listIssues", summary = "Issues the caller can see",
            description = "All issues of assigned companies for agents, all issues for admins, newest first "
                    + "(S0-AC-47).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    Paged<IssueSummary> listIssues(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return issues.list(CurrentAccount.get().viewer(), page, size).map(ConsoleIssueController::summary);
    }

    @Operation(operationId = "getIssue", summary = "One issue")
    @ApiResponse(responseCode = "200", description = "The issue.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it, "
            + "for example after being unassigned (COM-1, PROJ-4).")
    @GetMapping("/{key}")
    Issue getIssue(@PathVariable String key) {
        return issue(issues.get(CurrentAccount.get().viewer(), key));
    }
}
