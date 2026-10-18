package io.popdesk;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The body of every error response (docs/integrate/conventions.md "Error response", ADR D-44).
 * {@code fields} is present only when the error is about specific fields, {@code issueKeys} only when
 * it is about other issues (409 {@code RESTORE_LINK_CONFLICT}), {@code brokenSeq} only when the audit
 * chain is broken (409 {@code AUDIT_CHAIN_BROKEN}).
 */
@Schema(description = "Error body. Clients choose the text they show by `code`; `message` is a default text "
        + "for a `code` they do not know.")
public record ApiError(
        @Schema(description = "Stable error key. See docs/integrate/conventions.md for the list.") String code,
        String message,
        @Schema(description = "Present only when the error is about specific fields (400 `INVALID_INPUT`).")
                @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> fields,
        @Schema(description = "Present only when the error is about other issues: the keys of the other issues "
                + "whose links stop a restore (409 `RESTORE_LINK_CONFLICT`, S7A-AC-24).")
                @JsonInclude(JsonInclude.Include.NON_NULL) List<String> issueKeys,
        @Schema(description = "Present only when the audit log's hash chain is broken (409 `AUDIT_CHAIN_BROKEN`, "
                + "S10A-AC-37): the `seq` of the first entry that does not hold.")
                @JsonInclude(JsonInclude.Include.NON_NULL) Long brokenSeq) {

    public ApiError(String code, String message, List<FieldError> fields) {
        this(code, message, fields, null, null);
    }

    public record FieldError(
            @Schema(description = "Name of the request field or query parameter.") String field,
            @Schema(description = "Stable field error key, for example `TOO_LONG`.") String code) {}
}
