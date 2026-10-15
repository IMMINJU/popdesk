package io.popdesk.helpdesk.web.console;

import io.popdesk.PatchText;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.label.LabelRepository.LabelRow;
import io.popdesk.helpdesk.label.LabelService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Labels of a customer company (LBL-1 to LBL-4). Admins create, change, and delete them; staff who see
 * the company read them. Putting a label on an issue is under the issue ({@code ConsoleIssueController}).
 */
@Tag(name = "Companies")
@RestController
@RequestMapping("/api/console/companies/{code}/labels")
class ConsoleLabelController {

    static final String LABEL_NOT_FOUND = "`NOT_FOUND`: no such company, or no such label in this company: a "
            + "label of another company is answered like one that does not exist (S6A-AC-12).";
    static final String NAME_TAKEN = "`LABEL_NAME_TAKEN`: the company has a label of this name, whatever the case "
            + "(S6A-AC-03).";

    private final LabelService labels;

    ConsoleLabelController(LabelService labels) {
        this.labels = labels;
    }

    private static Label label(LabelRow row) {
        return new Label(row.id(), row.name(), row.color(), row.issueCount());
    }

    @Operation(operationId = "listCompanyLabels", summary = "Labels of a company",
            description = "Every label of the company, at most 200, in name order whatever the case, each with "
                    + "the number of issues that have it (S6A-AC-10). All at once, without pages: the list fills "
                    + "a picker. Also for an `ARCHIVED` company.")
    @ApiResponse(responseCode = "200", description = "The labels.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = ConsoleCompanyController.NOT_FOUND + " An agent who is not "
            + "assigned to the company gets this too (S6A-AC-11).")
    @GetMapping
    CompanyLabels listCompanyLabels(@PathVariable String code) {
        return new CompanyLabels(labels.list(CurrentAccount.get().viewer(), code).stream()
                .map(ConsoleLabelController::label).toList());
    }

    @Operation(operationId = "createLabel", summary = "Create a label in a company",
            description = "A company has at most 200 labels (S6A-AC-09). Nothing can be added to an `ARCHIVED` "
                    + "company (S6A-AC-08).")
    @ApiResponse(responseCode = "201", description = "Created, with `issueCount` 0.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `name` missing (`REQUIRED`), only "
            + "whitespace (`BLANK`), or over 30 characters (`TOO_LONG`); `color` not `#` and six hexadecimal "
            + "digits (`INVALID_FORMAT`); `fields` lists all (S6A-AC-02, S6A-AC-04). `COMPANY_NOT_ACTIVE`: the "
            + "company is `ARCHIVED` (S6A-AC-08). `LABEL_LIMIT_EXCEEDED`: the company has 200 labels already "
            + "(S6A-AC-09).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ConsoleCompanyController.ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = ConsoleCompanyController.NOT_FOUND)
    @ApiResponse(responseCode = "409", description = NAME_TAKEN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Label createLabel(@PathVariable String code, @RequestBody CreateLabelRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return label(labels.create(admin.viewer(), admin.actor(), code, request.name(), request.color()));
    }

    @Operation(operationId = "updateLabel", summary = "Change a label's name or color",
            description = "A field left out keeps its value. Issues that have the label show the new name and "
                    + "color; their history keeps the name the label had then, and the change itself leaves no "
                    + "history entry (S6A-AC-05). Works for an `ARCHIVED` company too (S6A-AC-08).")
    @ApiResponse(responseCode = "200", description = "Saved.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `name` only whitespace (`BLANK`), over 30 "
            + "characters (`TOO_LONG`), or `null` (`REQUIRED`); `color` not `#` and six hexadecimal digits "
            + "(`INVALID_FORMAT`) or `null` (`REQUIRED`); `fields` lists all (S6A-AC-02, S6A-AC-04).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ConsoleCompanyController.ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = LABEL_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = NAME_TAKEN + " Changing only the case of the label's own name "
            + "is not this.")
    @PatchMapping("/{id}")
    Label updateLabel(@PathVariable String code, @PathVariable long id, @RequestBody UpdateLabelRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return label(labels.update(admin.viewer(), admin.actor(), code, id, PatchText.of("name", request.name()),
                PatchText.of("color", request.color())));
    }

    @Operation(operationId = "deleteLabel", summary = "Delete a label",
            description = "Also takes it off every issue that has it; this leaves no history entry on those "
                    + "issues (S6A-AC-06, LBL-3). Works for an `ARCHIVED` company too (S6A-AC-08).")
    @ApiResponse(responseCode = "200", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ConsoleCompanyController.ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = LABEL_NOT_FOUND)
    @DeleteMapping("/{id}")
    void deleteLabel(@PathVariable String code, @PathVariable long id) {
        CurrentAccount admin = CurrentAccount.get();
        labels.delete(admin.viewer(), admin.actor(), code, id);
    }
}
