package io.popdesk.helpdesk.web.portal;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private static final Set<String> LIST_PARAMETERS = Set.of("page", "size", "status", "companyCode", "mine", "q");

    private final IssueService issues;
    private final AttachmentService attachments;

    PortalIssueController(IssueService issues, AttachmentService attachments) {
        this.issues = issues;
        this.attachments = attachments;
    }

    /**
     * The issue with whether its status and closed time allow reopening now, whoever asks (S3-AC-59). Of
     * the staff member who filed it for the reporter a customer learns only that the support team did
     * (S3-AC-36); of the assignee, nothing (S3-AC-29).
     */
    private Issue issue(IssueRow row) {
        return new Issue(row.key(), row.companyCode(), row.companyName(), row.title(), row.content(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole(), row.reporterPhotoUpdatedAt()),
                row.createdBy() != null, utc(row.resolvedAt()), utc(row.closedAt()), issues.reopenable(row), row.version(),
                utc(row.createdAt()), utc(row.updatedAt()));
    }

    static IssueSummary summary(IssueRow row) {
        return new IssueSummary(row.key(), row.title(), row.companyCode(), row.companyName(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole(), row.reporterPhotoUpdatedAt()),
                row.createdBy() != null, utc(row.resolvedAt()), utc(row.closedAt()), utc(row.createdAt()));
    }

    /**
     * A customer by name, with the time of the profile photo; staff only as the support team, without
     * name, ID, or anything of a photo (ADR D-49, S1A-AC-26).
     */
    static Person person(long accountId, String name, Role role, Instant photoUpdatedAt) {
        return role == Role.CUSTOMER
                ? new Person(PersonKind.CUSTOMER, accountId, name, photoUpdatedAt == null ? null : utc(photoUpdatedAt))
                : new Person(PersonKind.SUPPORT_TEAM, null, null, null);
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
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
    static final String IDEMPOTENCY_KEY = "Makes a retry safe: a filing with a key this account used in the last 24 "
            + "hours answers with the issue that key made (S3-AC-41). 1 to 100 visible ASCII characters. See "
            + "\"Filing an issue once\" in the conventions.";
    static final String KEY_ERRORS_400 = "`INVALID_INPUT` with field `Idempotency-Key` (`INVALID_FORMAT`, "
            + "`TOO_LONG`): the key is not 1 to 100 visible ASCII characters (S3-AC-45).";
    static final String KEY_REUSED_409 = "`IDEMPOTENCY_KEY_REUSED`: the account used this `Idempotency-Key` for a "
            + "filing with other content (S3-AC-42).";
    static final String NOT_VISIBLE = "`NOT_FOUND`: no such issue, or the caller cannot see it (COM-1).";
    static final String NOT_REPORTER = "`FORBIDDEN`: the caller is not the reporter";

    static final String FILE_ERRORS_400 = "With files: `FILE_EMPTY`, `FILE_TOO_LARGE` (over 10 MB), "
            + "`ISSUE_ATTACHMENTS_TOO_LARGE` (public attachments of the issue over 50 MB), `ATTACHMENT_QUOTA_EXCEEDED` "
            + "(team quota), and `INVALID_INPUT` with field `files` `TOO_LONG` (file name over 255) "
            + "(S0-AC-57, S0-AC-58). Nothing is created then (S0-AC-63).";

    @Operation(operationId = "createIssue", summary = "File an issue",
            description = "Files an issue in a company the caller belongs to (S0-AC-41): key `{code}-{number}`, "
                    + "status `RECEIVED`, channel `PORTAL`. Send JSON, or `multipart/form-data` with the issue as JSON "
                    + "in part `issue` and files in parts `files` to attach them in the same request (S0-AC-56).")
    @ApiResponse(responseCode = "201", description = "Created. Also for a retry with the same "
            + "`Idempotency-Key` and content: the issue made the first time, as it is now (S3-AC-41).")
    @ApiResponse(responseCode = "400", description = CREATE_ERRORS_400 + " " + KEY_ERRORS_400 + " "
            + FILE_ERRORS_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: the caller does not belong to the company "
            + "(S0-AC-43).")
    @ApiResponse(responseCode = "409", description = KEY_REUSED_409)
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the request is over the upload limit, or "
            + "has more than 20 files (S1A-AC-65).")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 10 issues in 60 seconds (S0-AC-50). "
            + "The files of a filing also count toward the limit on attachment uploads, 30 in 60 seconds "
            + "(S1A-AC-66).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 uploads are running; try again after "
            + "`Retry-After` (S0-AC-99).")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssue(
            @Parameter(description = IDEMPOTENCY_KEY)
                    @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody CreateIssueRequest request) {
        Viewer viewer = CurrentAccount.get().viewer();
        IssueService.Draft draft = issues.prepare(viewer, received(request));
        return issue(issues.file(viewer, draft));
    }

    @Operation(operationId = "createIssue", summary = "File an issue")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssueWithFiles(
            @Parameter(description = IDEMPOTENCY_KEY)
                    @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestPart("issue") CreateIssueRequest issue,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        Viewer viewer = CurrentAccount.get().viewer();
        boolean withFiles = files != null && !files.isEmpty();
        if (withFiles) {
            // How many files there are, and the upload limit, before anything else (S1A-AC-65, S1A-AC-66).
            attachments.countFiling(viewer, files);
        }
        // The company and the fields are checked before any file is looked at, every file before any is
        // written, and the files are written before the filing transaction begins (S0-AC-56, S0-AC-63).
        IssueService.Draft draft = issues.prepare(viewer, received(issue));
        if (!withFiles) {
            return issue(issues.file(viewer, draft));
        }
        List<UUID> pending = attachments.writeFiling(viewer, files);
        IssueRow filed;
        try {
            filed = issues.file(viewer, draft, () -> attachments.storeFiled(pending, draft.id()));
        } catch (RuntimeException e) {
            // Outside the failed transaction and before the response (verify.md V-9).
            attachments.discardEnded(pending);
            throw e;
        }
        attachments.reviewQuota();
        return issue(filed);
    }

    @Operation(operationId = "listIssues", summary = "Issues the caller can see",
            description = "Public issues of the caller's companies and every issue the caller reported, newest "
                    + "first (S0-AC-47). The filters narrow that and never widen it: with several, an issue "
                    + "must match all of them (S3-AC-08, S3-AC-10).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), unknown `status` (`UNKNOWN_VALUE`), `q` empty or over 100 characters "
            + "(`TOO_SHORT`, `TOO_LONG`, S3-AC-14), or a parameter customers may not use, such as `assignee`, "
            + "`reporter`, `label`, `sla`, `sort` (`UNKNOWN_PARAMETER`, S0-AC-48, S3-AC-09).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 60 lists with `q` in 60 seconds "
            + "(S3-AC-15).")
    @GetMapping
    Paged<IssueSummary> listIssues(
            @Parameter(description = "Only issues with one of these statuses. Give it once for each status.")
                    @RequestParam(required = false) @Nullable List<IssueStatus> status,
            @Parameter(description = "Only issues of the company with this code. A company the caller does not "
                    + "belong to gives an empty page.") @RequestParam(required = false) @Nullable String companyCode,
            @Parameter(description = "`true`: only issues the caller reported.")
                    @RequestParam(required = false) @Nullable Boolean mine,
            @Parameter(description = "Only issues whose title or content, as shown, holds this text, 1 to 100 "
                    + "characters. Case is ignored; `%` and `_` are ordinary characters (COM-5).")
                    @RequestParam(required = false) @Nullable String q,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        List<ApiError.FieldError> unknown = request.getParameterMap().keySet().stream()
                .filter(name -> !LIST_PARAMETERS.contains(name)).sorted()
                .map(name -> new ApiError.FieldError(name, "UNKNOWN_PARAMETER")).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid(unknown);
        }
        Viewer viewer = CurrentAccount.get().viewer();
        // "Mine" is the reporter filter with the caller's own account; a customer names no other (S3-AC-09).
        Long reporter = Boolean.TRUE.equals(mine) ? viewer.accountId() : null;
        return issues.list(viewer, new IssueService.Filters(status, companyCode, null, reporter), page, size)
                .map(PortalIssueController::summary);
    }

    @Operation(operationId = "getIssue", summary = "One issue")
    @ApiResponse(responseCode = "200", description = "The issue.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @GetMapping("/{key}")
    Issue getIssue(@PathVariable String key) {
        return issue(issues.get(CurrentAccount.get().viewer(), key));
    }

    @Operation(operationId = "updateIssue", summary = "Change an issue's title, content, or priority",
            description = "For the reporter. A field left out keeps its value; the rules for each field are those "
                    + "of filing (S3-AC-18). Saving goes up `version` by 1; sending what the issue already has "
                    + "changes nothing and is 200 too (S3-AC-22).")
    @ApiResponse(responseCode = "200", description = "The issue as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `version` missing (`REQUIRED`), `title` "
            + "empty, over 200, or `null`, `content` over 10,000, `priority` unknown or `null`; `fields` lists "
            + "all. `ISSUE_CLOSED`: the issue is `CLOSED` (S3-AC-21).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_REPORTER + ", or no longer belongs to the company "
            + "(S3-AC-20). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @ApiResponse(responseCode = "409", description = "`VERSION_CONFLICT`: `version` is not the issue's current "
            + "one; nothing changed. Read the issue again (S3-AC-19).")
    @PatchMapping("/{key}")
    Issue updateIssue(@PathVariable String key, @RequestBody UpdateIssueRequest request) {
        return issue(issues.edit(CurrentAccount.get().viewer(), key, new IssueService.Edit(
                PatchText.of("title", request.title()), PatchText.of("content", request.content()),
                PatchText.of("priority", request.priority()), request.version())));
    }

    @Operation(operationId = "closeIssue", summary = "Close an issue",
            description = "For the reporter, from any status but `CLOSED`, also after leaving the company "
                    + "(S3-AC-53, S3-AC-54). On a `RESOLVED` issue this confirms the resolution. Takes no body.")
    @ApiResponse(responseCode = "200", description = "The issue, now `CLOSED`.")
    @ApiResponse(responseCode = "400", description = "`ISSUE_CLOSED`: the issue is `CLOSED` already.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_REPORTER + " (S3-AC-55). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @PostMapping("/{key}/close")
    Issue closeIssue(@PathVariable String key) {
        return issue(issues.close(CurrentAccount.get().viewer(), key));
    }

    @Operation(operationId = "reopenIssue", summary = "Reopen an issue",
            description = "For the reporter: a `RESOLVED` issue, or a `CLOSED` one within 14 days of `closedAt`, "
                    + "goes back to `RECEIVED`, and `resolvedAt` and `closedAt` are cleared (S3-AC-56). Takes no "
                    + "body.")
    @ApiResponse(responseCode = "200", description = "The issue, now `RECEIVED`.")
    @ApiResponse(responseCode = "400", description = "`NOT_REOPENABLE`: the issue is neither `RESOLVED` nor "
            + "`CLOSED`, or it was closed 14 days ago or longer (S3-AC-57).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = NOT_REPORTER + ", or no longer belongs to the company "
            + "(S3-AC-55, S3-AC-58). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @PostMapping("/{key}/reopen")
    Issue reopenIssue(@PathVariable String key) {
        return issue(issues.reopen(CurrentAccount.get().viewer(), key));
    }
}
