package io.popdesk.helpdesk.web.portal;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Issues")
@RestController
@RequestMapping("/api/portal/issues")
class PortalIssueController {

    /** Query parameters of the list; anything else, such as a staff-only filter, is 400 (S0-AC-48). */
    private static final Set<String> LIST_PARAMETERS = Set.of("page", "size");

    private final IssueService issues;

    PortalIssueController(IssueService issues) {
        this.issues = issues;
    }

    static Issue issue(IssueRow row) {
        return new Issue(row.key(), row.companyCode(), row.companyName(), row.title(), row.content(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole()),
                row.version(), utc(row.createdAt()), utc(row.updatedAt()));
    }

    static IssueSummary summary(IssueRow row) {
        return new IssueSummary(row.key(), row.title(), row.companyCode(), row.companyName(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole()),
                utc(row.createdAt()));
    }

    /** A customer by name; staff only as the support team, without name or ID (ADR D-49). */
    static Person person(long accountId, String name, Role role) {
        return role == Role.CUSTOMER ? new Person(PersonKind.CUSTOMER, accountId, name)
                : new Person(PersonKind.SUPPORT_TEAM, null, null);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static IssueService.NewIssue received(CreateIssueRequest request) {
        String unknownPriority = ReceivedPriority.unknown();
        String priority = unknownPriority != null ? unknownPriority
                : request.priority() == null ? null : request.priority().name();
        return new IssueService.NewIssue(request.companyCode(), request.title(), request.content(), priority,
                request.privateIssue());
    }

    static final String CREATE_ERRORS_400 = "`INVALID_INPUT`: `title` empty or over 200, `content` over 10,000, "
            + "`priority` unknown; `fields` lists all (S0-AC-42). `COMPANY_NOT_ACTIVE`: the company is not `ACTIVE`.";
    static final String FILE_ERRORS_400 = "With files: `FILE_EMPTY`, `FILE_TOO_LARGE` (over 10 MB), "
            + "`ISSUE_ATTACHMENTS_TOO_LARGE` (public attachments of the issue over 50 MB), `ATTACHMENT_QUOTA_EXCEEDED` "
            + "(team quota), and `INVALID_INPUT` with field `files` `TOO_LONG` (file name over 255) "
            + "(S0-AC-57, S0-AC-58). Nothing is created then (S0-AC-63).";

    @Operation(operationId = "createIssue", summary = "File an issue",
            description = "Files an issue in a company the caller belongs to (S0-AC-41): key `{code}-{number}`, "
                    + "status `RECEIVED`, channel `PORTAL`. Send JSON, or `multipart/form-data` with the issue as JSON "
                    + "in part `issue` and files in parts `files` to attach them in the same request (S0-AC-56).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = CREATE_ERRORS_400 + " " + FILE_ERRORS_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: the caller does not belong to the company "
            + "(S0-AC-43).")
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the request is over the upload limit.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 10 issues in 60 seconds (S0-AC-50).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 uploads are running; try again after "
            + "`Retry-After` (S0-AC-99).")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssue(@RequestBody CreateIssueRequest request) {
        Viewer viewer = CurrentAccount.get().viewer();
        IssueService.Draft draft = issues.prepare(viewer, received(request));
        return issue(issues.file(viewer, draft));
    }

    @Operation(operationId = "createIssue", summary = "File an issue")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssueWithFiles(
            @RequestPart("issue") CreateIssueRequest issue,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        Viewer viewer = CurrentAccount.get().viewer();
        // The company is checked before any file is looked at, and an empty file before a number is taken.
        IssueService.Draft draft = issues.prepare(viewer, received(issue));
        List<MultipartFile> received = files == null ? List.of() : files;
        if (received.stream().anyMatch(MultipartFile::isEmpty)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "The file is empty.");
        }
        if (!received.isEmpty()) {
            // Storing the files of a filing is not built yet (s0-i-att).
            throw new NotImplementedException();
        }
        return issue(issues.file(viewer, draft));
    }

    @Operation(operationId = "listIssues", summary = "Issues the caller can see",
            description = "Public issues of the caller's companies and every issue the caller reported, newest "
                    + "first (S0-AC-47). Takes only `page` and `size`.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), or a parameter customers may not use, such as `assignee`, `label`, `sla`, `sort` "
            + "(`UNKNOWN_PARAMETER`, S0-AC-48).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    Paged<IssueSummary> listIssues(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        List<ApiError.FieldError> unknown = request.getParameterMap().keySet().stream()
                .filter(name -> !LIST_PARAMETERS.contains(name)).sorted()
                .map(name -> new ApiError.FieldError(name, "UNKNOWN_PARAMETER")).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid(unknown);
        }
        return issues.list(CurrentAccount.get().viewer(), page, size).map(PortalIssueController::summary);
    }

    @Operation(operationId = "getIssue", summary = "One issue")
    @ApiResponse(responseCode = "200", description = "The issue.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it "
            + "(COM-1).")
    @GetMapping("/{key}")
    Issue getIssue(@PathVariable String key) {
        return issue(issues.get(CurrentAccount.get().viewer(), key));
    }
}
