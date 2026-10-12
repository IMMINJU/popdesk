package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.comment.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
@RequestMapping("/api/console/issues/{key}/comments")
class ConsoleCommentController {

    private final CommentService comments;

    ConsoleCommentController(CommentService comments) {
        this.comments = comments;
    }

    static Comment comment(CommentRow row) {
        return new Comment(row.id(), ConsoleIssueController.person(row.authorId(), row.authorName(), row.authorRole(),
                row.authorPhotoUpdatedAt()), row.internal(),
                row.content(), ConsoleIssueController.utc(row.createdAt()), null, false, null, null, List.of());
    }

    static final String NOT_FOUND = "`NOT_FOUND`: no such issue or comment, the caller cannot see the issue, or the "
            + "comment belongs to another issue (S4-AC-03).";
    static final String RATE_LIMITED = "`RATE_LIMITED`: more than 30 comments added or edited in 60 seconds "
            + "(S0-AC-55, S4-AC-09).";

    @Operation(operationId = "listComments", summary = "Comments of an issue",
            description = "Public and internal comments, oldest first, with author names (S0-AC-54). A deleted "
                    + "comment stays in its place as a trace and is counted (`deleted`, S4-AC-10).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @GetMapping
    Paged<Comment> listComments(
            @PathVariable String key,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return comments.list(CurrentAccount.get().viewer(), key, page, size).map(ConsoleCommentController::comment);
    }

    @Operation(operationId = "createComment", summary = "Add a public or internal comment",
            description = "A public comment emails the reporter and sets the first response time once (S0-AC-46, "
                    + "S0-AC-70). An internal comment is hidden from customers (S0-AC-53). `mentions` names "
                    + "accounts to tell about the comment (S4-AC-19).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty or over 4,000; `mentions` "
            + "over 10 (`TOO_MANY`, S4-AC-21) or with an account that cannot be mentioned here (`UNKNOWN_VALUE`, "
            + "one answer whatever the reason, S4-AC-20). `ISSUE_CLOSED`: the issue is closed.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Comment createComment(@PathVariable String key, @RequestBody CreateCommentRequest request) {
        return comment(comments.add(CurrentAccount.get().viewer(), key, request.content(),
                Boolean.TRUE.equals(request.internal())));
    }

    @Operation(operationId = "updateComment", summary = "Edit the caller's own comment",
            description = "Only the author edits, an admin neither (S4-AC-02). A field left out keeps its value; a "
                    + "body with neither field changes nothing and is 200 too. A new `content` sets `editedAt`; "
                    + "the content the comment already has does not (S4-AC-06). `mentions` replaces the list, and "
                    + "only accounts new to it are told (S4-AC-24).")
    @ApiResponse(responseCode = "200", description = "The comment as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty, over 4,000, or `null`; "
            + "`mentions` over 10 (`TOO_MANY`) or with an account that cannot be mentioned here (`UNKNOWN_VALUE`); "
            + "`fields` lists all. `COMMENT_DELETED`: the comment is deleted (S4-AC-08). `ISSUE_CLOSED`: the issue "
            + "is `CLOSED` (S4-AC-04).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller did not write the comment. "
            + "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PatchMapping("/{id}")
    Comment updateComment(@PathVariable String key, @PathVariable long id,
            @RequestBody UpdateCommentRequest request) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "deleteComment", summary = "Delete a comment",
            description = "The author or an admin may delete, on a `CLOSED` issue too (S4-AC-13). The list keeps "
                    + "a trace: `content` is emptied and `deleted`, `deletedAt`, and `deletedBy` are set (S4-AC-10, "
                    + "COM-7). The comment's attachments are deleted with it (S4-AC-14). Deleting a deleted "
                    + "comment again is 204 too and changes nothing (S4-AC-12).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: an agent who did not write it. "
            + "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@PathVariable String key, @PathVariable long id) {
        throw new NotImplementedException();
    }
}
