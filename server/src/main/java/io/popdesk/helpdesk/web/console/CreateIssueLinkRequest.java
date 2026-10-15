package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** Both fields are read as text so that a missing or unknown value is the field error of the contract. */
public record CreateIssueLinkRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Key of the other issue. Missing, "
                + "`null`, or empty after the whitespace around it is removed is `REQUIRED`; a key the caller "
                + "cannot see, or a value that is not a key, is 404 (S6C-AC-02, S6C-AC-03).") String issueKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DUPLICATE_OF", "DUPLICATED_BY",
                "RELATED"}, description = "Seen from this issue (S6C-AC-13): `DUPLICATE_OF` makes this issue a "
                + "duplicate of the other one, `DUPLICATED_BY` the other one a duplicate of this one. Missing or "
                + "`null` is `REQUIRED`, any other value `UNKNOWN_VALUE`.") String type) {}
