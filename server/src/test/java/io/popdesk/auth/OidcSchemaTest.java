package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two tables of migration V24 (S11-AC-35, {@code design.md} 1절), read from the database: the names are the ones
 * the design gives, so this test reads the schema itself (the exception the slice allows for a migration). The rules
 * of every business table ({@code tenant_id}, time columns with a zone) are the tests of {@code start}
 * ({@code TenantIdTest}, {@code TimeTest}), which read every table of the schema and so these two too.
 */
class OidcSchemaTest extends BareAppTest {

    /** Column name to "type NULL" or "type NOT NULL", in the order of the table. */
    private Map<String, String> shape(String table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Column column : db.columns(table)) {
            out.put(column.name(), column.dataType() + (column.nullable() ? " NULL" : " NOT NULL"));
        }
        return out;
    }

    private static Map<String, String> columns(String... nameThenShape) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < nameThenShape.length; i += 2) {
            out.put(nameThenShape[i], nameThenShape[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("S11-AC-35 V24 ran, and oidc_link has its six columns with tenant_id bigint NOT NULL, a foreign key "
            + "to account, and the unique constraints oidc_link_issuer_subject_key (issuer, subject) and "
            + "oidc_link_account_issuer_key (account_id, issuer)")
    void oidcLinkTable() {
        assertThat(db.count("select count(*) from \"" + db.schema() + "\".flyway_schema_history "
                + "where version = '24' and success"))
                .as("V24 in flyway_schema_history").isEqualTo(1);

        assertThat(shape("oidc_link")).as("columns of oidc_link").isEqualTo(columns(
                "id", "bigint NOT NULL",
                "tenant_id", "bigint NOT NULL",
                "account_id", "bigint NOT NULL",
                "issuer", "text NOT NULL",
                "subject", "text NOT NULL",
                "linked_at", "timestamp with time zone NOT NULL"));
        Map<String, String> constraints = db.constraints("oidc_link");
        assertThat(constraints).as("constraints of oidc_link")
                .containsEntry("oidc_link_issuer_subject_key", "UNIQUE (issuer, subject)")
                .containsEntry("oidc_link_account_issuer_key", "UNIQUE (account_id, issuer)");
        List<String> accountKeys = constraints.values().stream()
                .filter(d -> d.equals("FOREIGN KEY (account_id) REFERENCES account(id)")).toList();
        assertThat(accountKeys).as("foreign key of oidc_link.account_id in %s", constraints).hasSize(1);
    }

    @Test
    @DisplayName("S11-AC-35 oidc_request has its nine columns with tenant_id bigint NOT NULL, the check "
            + "oidc_request_app_check on CONSOLE and PORTAL, the unique oidc_request_state_hash_key, and the index "
            + "oidc_request_created_at_idx")
    void oidcRequestTable() {
        assertThat(shape("oidc_request")).as("columns of oidc_request").isEqualTo(columns(
                "id", "bigint NOT NULL",
                "tenant_id", "bigint NOT NULL",
                "state_hash", "text NOT NULL",
                "browser_hash", "text NOT NULL",
                "nonce_hash", "text NOT NULL",
                "code_verifier", "text NOT NULL",
                "app", "text NOT NULL",
                "next_path", "text NOT NULL",
                "created_at", "timestamp with time zone NOT NULL"));
        Map<String, String> constraints = db.constraints("oidc_request");
        assertThat(constraints).as("constraints of oidc_request")
                .containsEntry("oidc_request_state_hash_key", "UNIQUE (state_hash)")
                .containsKey("oidc_request_app_check");
        assertThat(constraints.get("oidc_request_app_check")).as("oidc_request_app_check")
                .startsWith("CHECK").contains("'CONSOLE'").contains("'PORTAL'");
        assertThat(db.indexes("oidc_request")).as("indexes of oidc_request")
                .containsKey("oidc_request_created_at_idx");
        assertThat(db.indexes("oidc_request").get("oidc_request_created_at_idx"))
                .as("oidc_request_created_at_idx").endsWith("(created_at)");
    }
}
