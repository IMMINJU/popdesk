package io.popdesk.helpdesk.web.console;

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
@RequestMapping("/api/console/issues/{key}/comments")
class ConsoleCommentController {

    private final CommentService comments;

    ConsoleCommentController(CommentService comments) {
        this.comments = comments;
    }

    static Comment comment(CommentRow row) {
        return new Comment(row.id(), new Person(row.authorId(), row.authorName(), row.authorRole(), null), row.internal(),
                row.content(), ConsoleIssueController.utc(row.createdAt()));
    }

    @Operation(operationId = "listComments", summary = "Comments of an issue",
            description = "Public and internal comments, oldest first, with author names (S0-AC-54).")
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
                    + "S0-AC-70). An internal comment is hidden from customers (S0-AC-53).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` empty or over 4,000. "
            + "`ISSUE_CLOSED`: the issue is closed.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 30 comments in 60 seconds "
            + "(S0-AC-55).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Comment createComment(@PathVariable String key, @RequestBody CreateCommentRequest request) {
        return comment(comments.add(CurrentAccount.get().viewer(), key, request.content(),
                Boolean.TRUE.equals(request.internal())));
    }
}
