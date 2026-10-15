package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.link.LinkRepository.LinkRow;
import io.popdesk.helpdesk.link.LinkService;
import io.popdesk.helpdesk.link.LinkService.LinkView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Links between two issues (LINK-1 to LINK-7), for staff only. Linking and unlinking need both issues
 * visible to the caller; the list shows only links whose other issue the caller can see (S6C-AC-12).
 * Customers never reach these paths: a portal host answers 404 and a console host 401, as for every
 * console path (S6C-AC-40).
 */
@Tag(name = "Links")
@RestController
@RequestMapping("/api/console")
class ConsoleLinkController {

    static final String RATE_LIMITED = "`RATE_LIMITED`: more than 60 links made and removed in 60 seconds, counted "
            + "together, also those that ended in 400, 404, or 409; counted before any other check (S6C-AC-09, "
            + "COM-8).";
    static final String CLOSED = "`ISSUE_CLOSED`: this issue (the one in the path) is `CLOSED`; nothing changed. "
            + "The other issue may be `CLOSED` (S6C-AC-07).";
    static final String UNCHANGED = "Neither issue's `version` nor `updatedAt` changes (S6C-AC-23), nor its status "
            + "(S6C-AC-30). Both issues' histories get one `LINK` entry each (S6C-AC-20).";

    private final LinkService links;

    ConsoleLinkController(LinkService links) {
        this.links = links;
    }

    private static IssueLink link(LinkView view) {
        LinkRow row = view.row();
        return new IssueLink(IssueLinkType.valueOf(view.type().name()),
                new LinkedIssue(row.other().key(), row.other().title(), row.other().status(), row.other().companyCode(),
                        row.other().companyName()),
                ConsoleIssueController.person(row.createdBy().accountId(), row.createdBy().name(),
                        row.createdBy().role(), row.createdBy().photoUpdatedAt()),
                ConsoleIssueController.utc(row.createdAt()));
    }

    @Operation(operationId = "listIssueLinks", summary = "Links of an issue",
            description = "Only links whose other issue the caller can see, in the order they were made, all at "
                    + "once without pages (S6C-AC-12). Each link's `type` is seen from this issue (S6C-AC-13). "
                    + "Also on a `CLOSED` issue.")
    @ApiResponse(responseCode = "200", description = "The links the caller can see.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = ConsoleIssueController.NOT_VISIBLE)
    @GetMapping("/issues/{key}/links")
    IssueLinks listIssueLinks(@PathVariable String key) {
        return new IssueLinks(links.list(CurrentAccount.get().viewer(), key).stream()
                .map(ConsoleLinkController::link).toList());
    }

    @Operation(operationId = "createIssueLink", summary = "Link an issue to another issue",
            description = "Makes one link between this issue and the issue `issueKey`, which may belong to "
                    + "another company (S6C-AC-01). Two issues have at most one link, of any kind and direction "
                    + "(S6C-AC-04). A duplicate has one original, and an original is never a duplicate "
                    + "(S6C-AC-05). An issue has at most 50 links, counting those the caller cannot see "
                    + "(S6C-AC-06). " + UNCHANGED + " Checks, in this order (S6C-AC-14): rate limit (429), this "
                    + "issue (404), `issueKey` present (400 `REQUIRED`), the other issue (404), `type` (400), the "
                    + "same issue (400 `LINK_TO_SELF`), this issue `CLOSED` (400), a link already there (409), "
                    + "a duplicate chain (400), the limit (400). So a wrong `type` with an issue the caller cannot "
                    + "see is 404.")
    @ApiResponse(responseCode = "201", description = "Created; the link as seen from this issue.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `issueKey` missing, `null`, or empty after "
            + "the whitespace around it is removed (`REQUIRED`); `type` missing or `null` (`REQUIRED`) or not "
            + "`DUPLICATE_OF`, `DUPLICATED_BY`, or `RELATED` (`UNKNOWN_VALUE`, S6C-AC-03). `LINK_TO_SELF`: "
            + "`issueKey` is this issue (S6C-AC-03). " + CLOSED + " `LINK_DUPLICATE_CHAIN`: as a duplicate link, "
            + "the original would already be a duplicate, or the duplicate is already an original or already a "
            + "duplicate of another issue; `RELATED` is not checked (S6C-AC-05). `LINK_LIMIT_EXCEEDED`: this "
            + "issue or the other one has 50 links already (S6C-AC-06).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it, "
            + "for this issue or the other one; also when `issueKey` is not a key. The same body whether the "
            + "other issue does not exist or the caller cannot see it (S6C-AC-02).")
    @ApiResponse(responseCode = "409", description = "`LINK_EXISTS`: the two issues are linked already, of any "
            + "kind and direction; nothing changed (S6C-AC-04).")
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PostMapping("/issues/{key}/links")
    @ResponseStatus(HttpStatus.CREATED)
    IssueLink createIssueLink(@PathVariable String key, @RequestBody CreateIssueLinkRequest request) {
        return link(links.create(CurrentAccount.get().viewer(), key, request.issueKey(), request.type()));
    }

    @Operation(operationId = "deleteIssueLink", summary = "Remove the link between two issues",
            description = "Removes the link between this issue and the issue `otherKey`, from either side "
                    + "(S6C-AC-10). The two issues can be linked again afterwards. " + UNCHANGED + " Checks, in "
                    + "this order (S6C-AC-14): rate limit (429), this issue (404), the other issue (404), the "
                    + "link (404), this issue `CLOSED` (400).")
    @ApiResponse(responseCode = "204", description = "Removed.")
    @ApiResponse(responseCode = "400", description = CLOSED)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it, "
            + "for this issue or the other one, for example after being unassigned from its company; or the two "
            + "issues are not linked. The link stays as it was (S6C-AC-11).")
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @DeleteMapping("/issues/{key}/links/{otherKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteIssueLink(@PathVariable String key, @PathVariable String otherKey) {
        links.delete(CurrentAccount.get().viewer(), key, otherKey);
    }
}
