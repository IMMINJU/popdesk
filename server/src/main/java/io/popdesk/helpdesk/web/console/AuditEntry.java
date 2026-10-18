package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One audit entry (S1B-AC-01, S1B-AC-07). {@code target} and {@code changes} are the stored JSON as it
 * is: they went into the hash, so no key is renamed on the way out.
 */
public record AuditEntry(
        long id,
        @Schema(description = "The entry's place in the hash chain, from 1.") long seq,
        @Schema(description = "The kind of action: a value of the `action` filter. An entry someone changed in "
                + "the database may hold another name, so this is a plain string.") String action,
        AuditActor actor,
        @Schema(description = "What the action was done to, as stored: `type` (`ACCOUNT`, `COMPANY`, "
                + "`DATABASE`, `AUDIT_LOG`, `SLA_POLICY`, `BRANDING`) and the keys that name it (`accountId`, `code`). An issue deleted, "
                + "restored, or purged by an admin (`ISSUE_DELETED`, `ISSUE_RESTORED`, `ISSUE_PURGED`) is "
                + "`{\"type\": \"COMPANY\", \"code\": …, \"issueKey\": …}`, with the issue's company and key and "
                + "never its title, and `changes` is null (S7A-AC-07). A change of the SLA policy "
                + "(`SLA_POLICY_CHANGED`) is `{\"type\": \"SLA_POLICY\", \"version\": …}` with the new version "
                + "(S8A-AC-06). A change of the display name or the accent color (`BRANDING_CHANGED`) is "
                + "`{\"type\": \"BRANDING\"}` (S9-AC-20); a new or removed logo (`BRANDING_LOGO_CHANGED`) is "
                + "`{\"type\": \"BRANDING\", \"theme\": …}` with `LIGHT` or `DARK` (S9-AC-21). An account "
                + "export or anonymization (`ACCOUNT_EXPORTED`, `ACCOUNT_ANONYMIZED`) is "
                + "`{\"type\": \"ACCOUNT\", \"accountId\": …}`; the chain computed again after an anonymization "
                + "(`AUDIT_RECHAINED`) is `{\"type\": \"AUDIT_LOG\"}` (S10A-AC-06, S10A-AC-16, S10A-AC-18).",
                nullable = true)
                Map<String, Object> target,
        @Schema(description = "What changed, as stored. A changed value is `{\"from\": ..., \"to\": ...}` under "
                + "its field name; a value set for the first time is the value itself. Null when the action "
                + "changes no value.", nullable = true) Map<String, Object> changes,
        @Schema(description = "When the action was done, with microseconds.") OffsetDateTime occurredAt,
        @Schema(description = "SHA-256 of `prevHash` and the entry's canonical JSON: 64 lowercase hex characters.",
                pattern = "^[0-9a-f]{64}$") String hash,
        @Schema(description = "The `hash` of the entry before this one in the chain; 64 zeros for the first.",
                pattern = "^[0-9a-f]{64}$") String prevHash) {}
