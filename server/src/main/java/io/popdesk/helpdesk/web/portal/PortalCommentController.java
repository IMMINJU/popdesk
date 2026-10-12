package io.popdesk.helpdesk.web.portal;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.comment.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Comments")
@RestController
@RequestMapping("/api/portal/issues/{key}/comments")
class PortalCommentController {

    private final CommentService comments;

    PortalCommentController(CommentService comments) {
        this.comments = comments;
    }

    static Comment comment(CommentRow row) {
        return new Comment(row.id(), PortalIssueController.person(row.authorId(), row.authorName(), row.authorRole(),
                row.authorPhotoUpdatedAt()),
                row.content(), PortalIssueController.utc(row.createdAt()), PortalIssueController.utc(row.editedAt()),
                row.deleted(), PortalIssueController.utc(row.deletedAt()));
    }

    static final String NOT_FOUND = "`NOT_FOUND`: no such issue or comment, the caller cannot see the issue, the "
            + "comment belongs to another issue, or it is an internal comment (S4-AC-03).";
    static final String RATE_LIMITED = "`RATE_LIMITED`: more than 30 comments added or edited in 60 seconds "
            + "(S0-AC-55, S4-AC-09).";

    @Operation(operationId = "listComments", summary = "Public comments of an issue",
            description = "Oldest first (S0-AC-54). Internal comments are left out (S0-AC-53). Comments by staff "
                    + "have author `SUPPORT_TEAM` without name or ID (ADR D-49). A deleted comment stays in its "
                    + "place as a trace and is counted (`deleted`, S4-AC-10).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @GetMapping
    Paged<Comment> listComments(
            @PathVariable String key,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return comments.list(CurrentAccount.get().viewer(), key, page, size).map(PortalCommentController::comment);
    }

    @Operation(operationId = "createComment", summary = "Add a public comment",
            description = "Only the reporter may comment (S0-AC-51, S0-AC-52).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty or over 4,000, or not "
            + "text (`INVALID_FORMAT`). `ISSUE_CLOSED`: the issue is closed.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller can see the issue but is not its "
            + "reporter, or left the company (S0-AC-52, PROJ-4). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Comment createComment(@PathVariable String key, @RequestBody CreateCommentRequest request) {
        return comment(comments.add(CurrentAccount.get().viewer(), key, request.content(), false, null));
    }

    @Operation(operationId = "updateComment", summary = "Edit the caller's own comment",
            description = "Only the author edits (S4-AC-02). A body without `content` changes nothing and is 200 "
                    + "too. A new `content` sets `editedAt`; the content the comment already has does not "
                    + "(S4-AC-06).")
    @ApiResponse(responseCode = "200", description = "The comment as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty, over 4,000, or `null`, "
            + "or not text (`INVALID_FORMAT`). `COMMENT_DELETED`: the comment is deleted (S4-AC-08). `ISSUE_CLOSED`: the issue is `CLOSED` "
            + "(S4-AC-04).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller did not write the comment, or left "
            + "the company (S4-AC-05). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PatchMapping("/{id}")
    Comment updateComment(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id,
            @RequestBody UpdateCommentRequest request) {
        return comment(comments.edit(CurrentAccount.get().viewer(), key, id, request.content(), null));
    }

    @Operation(operationId = "deleteComment", summary = "Delete the caller's own comment",
            description = "Only the author deletes, on a `CLOSED` issue too (S4-AC-13). The list keeps a trace: "
                    + "`content` is emptied and `deleted` and `deletedAt` are set (S4-AC-10, COM-7). The comment's "
                    + "attachments are deleted with it (S4-AC-14). Deleting a deleted comment again is 204 too "
                    + "and changes nothing (S4-AC-12).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller did not write the comment, or left "
            + "the company (S4-AC-05, S4-AC-11). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id) {
        comments.delete(CurrentAccount.get().viewer(), key, id);
    }
}
