package io.popdesk.helpdesk.web.portal;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
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
        throw new NotImplementedException();
    }

    @Operation(operationId = "createIssue", summary = "File an issue")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Issue createIssueWithFiles(
            @RequestPart("issue") CreateIssueRequest issue,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        throw new NotImplementedException();
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
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "getIssue", summary = "One issue")
    @ApiResponse(responseCode = "200", description = "The issue.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it "
            + "(COM-1).")
    @GetMapping("/{key}")
    Issue getIssue(@PathVariable String key) {
        throw new NotImplementedException();
    }
}
