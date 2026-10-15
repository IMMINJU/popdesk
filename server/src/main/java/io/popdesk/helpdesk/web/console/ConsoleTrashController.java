package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trash (TRASH-2, TRASH-3), for admins only. An issue gets here through {@code deleteIssue}; under
 * {@code /api/console/issues} it is 404 from then on, so restoring and purging have paths of their own
 * (slice 7a acceptance section 5, 3). A purged issue is in no list and every path here answers 404 for it.
 */
@Tag(name = "Trash")
@RestController
@RequestMapping("/api/console/trash")
class ConsoleTrashController {

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only, checked before the issue (S7A-AC-02).";
    static final String NOT_IN_TRASH = "`NOT_FOUND`: no issue with this key is in the trash: no such issue, an "
            + "issue that is not deleted, or one that is purged already, for example by someone else just before "
            + "(S7A-AC-23). One body for all of these.";

    @Operation(operationId = "listTrash", summary = "Issues in the trash",
            description = "Every deleted issue that is not purged yet, of every company, the latest deleted first; "
                    + "issues deleted at the same moment by key, the highest number first (S7A-AC-20). Checks, in "
                    + "this order: role (403), `page` and `size` (400).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = ConsoleCompanyController.PAGE_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping
    Paged<TrashedIssue> listTrash(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "restoreIssue", summary = "Restore an issue from the trash",
            description = "The issue is back as it was before it was deleted: its content, comments, attachments, "
                    + "tasks and their notes, links, labels, assignee, due date, and status, and its inbox items "
                    + "are listed again (S7A-AC-21). What was not sent while it was in the trash is not sent now "
                    + "(S7A-AC-10). A `RESOLVED` issue is closed by popdesk 7 days after the restore, not after "
                    + "`resolvedAt`, which stays as it was (S7A-AC-22). Goes up `version` by 1 and leaves a "
                    + "history entry `TRASH` with the old value `TRASHED` (S7A-AC-06) and an audit entry "
                    + "`ISSUE_RESTORED` (S7A-AC-07). Takes no body. Checks, in this order: role (403), the issue "
                    + "in the trash (404), its links (409).")
    @ApiResponse(responseCode = "200", description = "The issue as it is now.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_IN_TRASH)
    @ApiResponse(responseCode = "409", description = "`RESTORE_LINK_CONFLICT`: with its links the issue would "
            + "break the link rules (LINK-2: over 50 links, or a duplicate chain); it stays in the trash and "
            + "nothing changed. `issueKeys` lists the keys of the other issues of the links at fault (S7A-AC-24).")
    @PostMapping("/{key}/restore")
    Issue restoreIssue(@PathVariable String key) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "purgeIssue", summary = "Purge an issue in the trash",
            description = "At once, without waiting for the 30 days (S7A-AC-30). What was written is deleted: the "
                    + "title and content (left empty), comments and mentions, attachments and their files, tasks "
                    + "and their notes, links, labels on the issue, its inbox items, its waiting messages, and the "
                    + "`Idempotency-Key` of its filing; the title and content values of its history entries are "
                    + "cleared. The key, company, status, times, and history entries stay, but the issue is shown "
                    + "nowhere again and its number is not used again (S7A-AC-32, S7A-AC-35). Leaves a history "
                    + "entry `TRASH` from `TRASHED` to `PURGED` (S7A-AC-06) and an audit entry `ISSUE_PURGED` "
                    + "(S7A-AC-07). Checks, in this order: role (403), the issue in the trash (404).")
    @ApiResponse(responseCode = "204", description = "Purged.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_IN_TRASH)
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void purgeIssue(@PathVariable String key) {
        throw new NotImplementedException();
    }
}
