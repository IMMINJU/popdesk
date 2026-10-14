package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record CreateLabelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 30 characters after the "
                + "whitespace around it is removed and the text is put in Unicode NFC. Unique in the company "
                + "whatever the case (S6A-AC-02, S6A-AC-03).") String name,
        @Schema(description = "`#` and six hexadecimal digits; small letters are stored as capitals (S6A-AC-04). "
                + "Left out or `null`: `#808080`.", nullable = true) @Nullable String color) {}
