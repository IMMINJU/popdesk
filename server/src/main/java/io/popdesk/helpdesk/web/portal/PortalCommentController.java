package io.popdesk.helpdesk.web.portal;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.comment.CommentRepository.CommentRow;
import io.popdesk.helpdesk.comment.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
                row.content(), PortalIssueController.utc(row.createdAt()));
    }

    @Operation(operationId = "listComments", summary = "Public comments of an issue",
            description = "Oldest first (S0-AC-54). Internal comments are left out (S0-AC-53). Comments by staff "
                    + "have author `SUPPORT_TEAM` without name or ID (ADR D-49).")
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
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty or over 4,000. "
            + "`ISSUE_CLOSED`: the issue is closed.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller can see the issue but is not its "
            + "reporter, or left the company (S0-AC-52, PROJ-4). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 30 comments in 60 seconds "
            + "(S0-AC-55).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Comment createComment(@PathVariable String key, @RequestBody CreateCommentRequest request) {
        return comment(comments.add(CurrentAccount.get().viewer(), key, request.content(), false));
    }
}
