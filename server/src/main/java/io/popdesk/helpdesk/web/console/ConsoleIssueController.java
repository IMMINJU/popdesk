package io.popdesk.helpdesk.web.console;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.issue.IssueHistoryRepository;
import io.popdesk.helpdesk.issue.IssueHistoryRepository.HistoryRow;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.issue.IssueService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Issues")
@RestController
@RequestMapping("/api/console/issues")
class ConsoleIssueController {

    /** Query parameters of the list; any other name is 400, so a mistyped filter is not ignored (S3-AC-07). */
    private static final Set<String> LIST_PARAMETERS = Set.of("page", "size", "status", "companyCode", "assignee",
            "reporter", "q");

    private final IssueService issues;

    ConsoleIssueController(IssueService issues) {
        this.issues = issues;
    }

    /**
     * The issue with what its status allows now: the transitions of staff and reopening (S3-AC-59). Staff
     * see who filed it for the reporter and who is assigned (S3-AC-28, S3-AC-36).
     */
    private Issue issue(IssueRow row) {
        return new Issue(row.key(), row.companyCode(), row.companyName(), row.title(), row.content(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole(), row.reporterPhotoUpdatedAt()),
                person(row.createdBy()), IssueChannel.valueOf(row.channel()), person(row.assignee()),
                row.assigneeUnavailable(), row.status().next(), issues.reopenable(row),
                utc(row.firstResponseAt()), utc(row.resolvedAt()), utc(row.closedAt()), row.version(),
                utc(row.createdAt()), utc(row.updatedAt()));
    }

    static IssueSummary summary(IssueRow row) {
        return new IssueSummary(row.key(), row.title(), row.companyCode(), row.companyName(), row.status(),
                row.priority(), row.privateIssue(), person(row.reporterId(), row.reporterName(), row.reporterRole(), row.reporterPhotoUpdatedAt()),
                row.createdBy() != null, person(row.assignee()), row.assigneeUnavailable(), utc(row.createdAt()));
    }

    /** Staff see every person by name, with the time of the profile photo for the photo's address. */
    static Person person(long accountId, String name, Role role, Instant photoUpdatedAt) {
        return new Person(accountId, name, role, utc(photoUpdatedAt));
    }

    private static Person person(IssueRepository.Staff person) {
        return person == null ? null
                : person(person.accountId(), person.name(), person.role(), person.photoUpdatedAt());
    }

    private static Person person(IssueHistoryRepository.Person person) {
        return person == null ? null
                : person(person.accountId(), person.name(), person.role(), person.photoUpdatedAt());
    }

    /** An assignee change names people; every other change gives its values as text (S3-AC-64). */
    private static IssueHistoryEntry entry(HistoryRow row) {
        return new IssueHistoryEntry(row.id(), IssueHistoryKind.valueOf(row.kind()), row.field(), row.oldValue(),
                row.newValue(), person(row.oldPerson()), person(row.newPerson()),
                new IssueHistoryActor(row.actorId(), row.actorName(), row.actorRole(), utc(row.actorPhotoUpdatedAt())),
                utc(row.createdAt()));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static final String NOT_VISIBLE = "`NOT_FOUND`: no such issue, or the caller cannot see it, for example after "
            + "being unassigned (COM-1, PROJ-4).";
    static final String IDEMPOTENCY_KEY = "Makes a retry safe: a filing with a key this account used in the last 24 "
            + "hours answers with the issue that key made (S3-AC-41). 1 to 100 visible ASCII characters. See "
            + "\"Filing an issue once\" in the conventions.";

    @Operation(operationId = "listIssues", summary = "Issues the caller can see",
            description = "All issues of assigned companies for agents, all issues for admins, newest first "
                    + "(S0-AC-47). The filters narrow that and never widen it: with several, an issue must match "
                    + "all of them (S3-AC-06).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), unknown `status` or `assignee` (`UNKNOWN_VALUE`), `reporter` not a number "
            + "(`INVALID_FORMAT`), `q` empty or over 100 characters (`TOO_SHORT`, `TOO_LONG`, S3-AC-14) or with a "
            + "character that cannot be stored (`INVALID_FORMAT`), or a parameter this list does not take "
            + "(`UNKNOWN_PARAMETER`, S3-AC-07).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 60 lists with `q` in 60 seconds, "
            + "counted together with the other lists that search: the account list and the list of customers an "
            + "issue can be filed for (S3-AC-15, COM-8).")
    @GetMapping
    Paged<IssueSummary> listIssues(
            @Parameter(description = "Only issues with one of these statuses. Give it once for each status.")
                    @RequestParam(required = false) @Nullable List<IssueStatus> status,
            @Parameter(description = "Only issues of the company with this code. A company the caller cannot see "
                    + "gives an empty page (S3-AC-02).") @RequestParam(required = false) @Nullable String companyCode,
            @Parameter(description = "Only issues with this assignee: an account ID, `me` for the caller, `none` "
                    + "for issues without an assignee, or `unavailable` for issues whose assignee can no longer "
                    + "see them (S3-AC-03, S3-AC-04).") @RequestParam(required = false) @Nullable String assignee,
            @Parameter(description = "Only issues this account reported. An account that does not exist gives an "
                    + "empty page (S3-AC-05).") @RequestParam(required = false) @Nullable Long reporter,
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
        return issues.list(CurrentAccount.get().viewer(),
                        new IssueService.Filters(status, companyCode, assignee, reporter, q), page, size)
                .map(ConsoleIssueController::summary);
    }

    @Operation(operationId = "createIssue", summary = "File an issue on behalf of a customer",
            description = "The customer is the reporter and the caller is `createdBy`; otherwise the issue is "
                    + "made like one the customer files: key `{code}-{number}`, status `RECEIVED` (S3-AC-32). "
                    + "The customer gets an email (S3-AC-37). JSON only; attach files to the issue afterwards.")
    @ApiResponse(responseCode = "201", description = "Created. Also for a retry with the same "
            + "`Idempotency-Key` and content: the issue made the first time, as it is now (S3-AC-41).")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `title` empty or over 200, `content` over "
            + "10,000, `priority` unknown, `channel` missing or not `PHONE` or `OTHER` (S3-AC-35), `reporterId` "
            + "missing, or not an `ACTIVE` customer who belongs to the company (`UNKNOWN_VALUE`, one answer "
            + "whatever the reason, S3-AC-33); `fields` lists all. `Idempotency-Key` not 1 to 100 visible ASCII "
            + "characters (S3-AC-45). `COMPANY_NOT_ACTIVE`: the company is not `ACTIVE`.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company, or the caller, an agent, is "
            + "not assigned to it (S3-AC-34).")
    @ApiResponse(responseCode = "409", description = "`IDEMPOTENCY_KEY_REUSED`: the account used this "
            + "`Idempotency-Key` for a filing with other content (S3-AC-42).")
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the body is over 256 KB.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 10 issues in 60 seconds "
            + "(S3-AC-39).")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssue(
            @Parameter(description = IDEMPOTENCY_KEY)
                    @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestBody CreateIssueRequest request) {
        Viewer viewer = CurrentAccount.get().viewer();
        IssueService.Prepared prepared = issues.prepareOnBehalf(viewer, new IssueService.NewIssueOnBehalf(
                new IssueService.NewIssue(request.companyCode(), request.title(), request.content(),
                        request.priority(), request.privateIssue()),
                request.reporterId(), request.channel()), idempotencyKey);
        return issue(prepared.filed() != null ? prepared.filed() : issues.file(viewer, prepared.draft()));
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
            description = "A field left out keeps its value; the rules for each field are those of filing "
                    + "(S3-AC-18). Saving goes up `version` by 1; sending what the issue already has changes "
                    + "nothing and is 200 too (S3-AC-22). Works whatever the status of the company (S3-AC-60).")
    @ApiResponse(responseCode = "200", description = "The issue as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `version` missing (`REQUIRED`), `title` "
            + "empty, over 200, or `null`, `content` over 10,000, `priority` unknown or `null`; `fields` lists "
            + "all. `ISSUE_CLOSED`: the issue is `CLOSED` (S3-AC-21).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @ApiResponse(responseCode = "409", description = "`VERSION_CONFLICT`: `version` is not the issue's current "
            + "one; nothing changed. Read the issue again (S3-AC-19).")
    @PatchMapping("/{key}")
    Issue updateIssue(@PathVariable String key, @RequestBody UpdateIssueRequest request) {
        return issue(issues.edit(CurrentAccount.get().viewer(), key, new IssueService.Edit(
                PatchText.of("title", request.title()), PatchText.of("content", request.content()),
                PatchText.of("priority", request.priority()), request.version())));
    }

    @Operation(operationId = "changeIssueStatus", summary = "Change an issue's status",
            description = "One of the issue's `nextStatuses` (STATUS-1). Takes no `version`: changes to one issue "
                    + "are applied one after another (S3-AC-51). `RESOLVED` sets `resolvedAt`, `CLOSED` sets "
                    + "`closedAt`, and leaving `RESOLVED` for `IN_PROGRESS` clears `resolvedAt` (S3-AC-50). A "
                    + "`CLOSED` issue leaves that status only through `reopenIssue`.")
    @ApiResponse(responseCode = "200", description = "The issue with its new status.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `status` missing (`REQUIRED`) or unknown "
            + "(`UNKNOWN_VALUE`). `INVALID_TRANSITION`: the issue cannot go from its status to this one, or has "
            + "it already; nothing changed (S3-AC-49).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @PutMapping("/{key}/status")
    Issue changeIssueStatus(@PathVariable String key, @RequestBody ChangeIssueStatusRequest request) {
        String unknown = ReceivedStatus.unknown();
        String status = unknown != null ? unknown : request.status() == null ? null : request.status().name();
        return issue(issues.changeStatus(CurrentAccount.get().viewer(), key, status));
    }

    @Operation(operationId = "reopenIssue", summary = "Reopen an issue",
            description = "A `RESOLVED` issue, or a `CLOSED` one within 14 days of `closedAt`, goes back to "
                    + "`RECEIVED`, and `resolvedAt` and `closedAt` are cleared; the assignee and "
                    + "`firstResponseAt` stay (S3-AC-56). Takes no body.")
    @ApiResponse(responseCode = "200", description = "The issue, now `RECEIVED`.")
    @ApiResponse(responseCode = "400", description = "`NOT_REOPENABLE`: the issue is neither `RESOLVED` nor "
            + "`CLOSED`, or it was closed 14 days ago or longer (S3-AC-57).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @PostMapping("/{key}/reopen")
    Issue reopenIssue(@PathVariable String key) {
        return issue(issues.reopen(CurrentAccount.get().viewer(), key));
    }

    @Operation(operationId = "setIssueAssignee", summary = "Set an issue's assignee",
            description = "One of the company's `listAssigneeCandidates` (ISSUE-7). Goes up `version` by 1; the "
                    + "assignee the issue already has changes nothing and is 200 too (S3-AC-24). Works on a "
                    + "`CLOSED` issue too (S3-AC-30).")
    @ApiResponse(responseCode = "200", description = "The issue with its assignee.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `accountId` missing (`REQUIRED`), or not "
            + "an account that can be the assignee (`UNKNOWN_VALUE`, one answer whatever the reason, S3-AC-25).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @PutMapping("/{key}/assignee")
    Issue setIssueAssignee(@PathVariable String key, @RequestBody SetAssigneeRequest request) {
        return issue(issues.assign(CurrentAccount.get().viewer(), key, request.accountId()));
    }

    @Operation(operationId = "clearIssueAssignee", summary = "Clear an issue's assignee",
            description = "An issue without an assignee stays as it is and is 200 too (S3-AC-26). Works on a "
                    + "`CLOSED` issue too (S3-AC-30).")
    @ApiResponse(responseCode = "200", description = "The issue without an assignee.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @DeleteMapping("/{key}/assignee")
    Issue clearIssueAssignee(@PathVariable String key) {
        return issue(issues.unassign(CurrentAccount.get().viewer(), key));
    }

    @Operation(operationId = "listIssueHistory", summary = "History of an issue",
            description = "One `CREATED` entry, then one `CHANGED` entry for each field that changed, oldest "
                    + "first (S3-AC-61, S3-AC-62, S3-AC-64). The portal has no such path (S3-AC-65).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @GetMapping("/{key}/history")
    Paged<IssueHistoryEntry> listIssueHistory(
            @PathVariable String key,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return issues.history(CurrentAccount.get().viewer(), key, page, size).map(ConsoleIssueController::entry);
    }

    @Operation(operationId = "listMentionCandidates", summary = "Who can be mentioned in a comment on the issue",
            description = "Every `ACTIVE` admin, every `ACTIVE` agent assigned to the issue's company, and, for a "
                    + "public comment, the reporter if `ACTIVE` and still able to see the issue; never the caller "
                    + "(S4-AC-22, MNT-1). In name order, all at once, without pages: the list fills a picker. No "
                    + "email addresses.")
    @ApiResponse(responseCode = "200", description = "The candidates.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `internal` neither `true` nor `false` "
            + "(`INVALID_FORMAT`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_VISIBLE)
    @GetMapping("/{key}/mention-candidates")
    MentionCandidates listMentionCandidates(
            @PathVariable String key,
            @Parameter(description = "`true`: for an internal comment, so without the reporter. Left out or "
                    + "`false`: for a public comment. Only these two spellings are taken: `TRUE`, `1`, or an "
                    + "empty value is 400.", schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(defaultValue = "false") String internal) {
        return new MentionCandidates(issues.mentionCandidates(CurrentAccount.get().viewer(), key, internal).stream()
                .map(p -> person(p.accountId(), p.name(), p.role(), p.photoUpdatedAt())).toList());
    }
}
