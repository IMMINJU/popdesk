package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables of migration V21 (S9-AC-33), read from the database: the names are the ones {@code design.md} 1절
 * gives, so this test reads the schema itself (the exception the slice allows for a migration). It runs in the
 * "bare" context, where no test changes the branding, so the row V21 put in is still the default.
 */
class BrandingSchemaTest extends BareAppTest {

    /** Column name to "data type, nullable" of a table. */
    private Map<String, String> columns(String table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Column c : db.columns(table)) {
            out.put(c.name(), c.dataType() + (c.nullable() ? " null" : " not null"));
        }
        return out;
    }

    /** The CHECK constraints of a table as {@code pg_get_constraintdef} writes them. */
    private List<String> checks(String table) {
        return db.constraints(table).values().stream().filter(d -> d.startsWith("CHECK (")).toList();
    }

    /** A definition without spaces and parentheses, in lower case. */
    private static String plain(String definition) {
        return definition.replaceAll("[\\s()]", "").toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("S9-AC-33 V21 adds the table branding: one row (id 1), tenant_id bigint not null, the display name "
            + "of up to 50 characters, the accent color #RRGGBB in capitals or null, a version, and timestamptz")
    void brandingTable() {
        Map<String, String> branding = columns("branding");

        assertThat(branding).as("columns of branding").containsEntry("id", "smallint not null")
                .containsEntry("tenant_id", "bigint not null")
                .containsEntry("display_name", "text not null")
                .containsEntry("accent", "text null")
                .containsEntry("version", "bigint not null")
                .containsEntry("changed_by", "bigint null")
                .containsEntry("changed_at", "timestamp with time zone not null");
        List<String> checks = checks("branding");
        assertThat(checks).as("check constraints of branding")
                .anySatisfy(c -> assertThat(plain(c)).contains("id=1"))
                .anySatisfy(c -> assertThat(plain(c)).contains("display_name").contains("<=50"))
                .anySatisfy(c -> assertThat(c).contains("accent").contains("^#[0-9A-F]{6}$"));
        assertThat(db.constraints("branding").values()).as("constraints of branding")
                .anySatisfy(c -> assertThat(plain(c)).isEqualTo("primarykeyid"));
    }

    @Test
    @DisplayName("S9-AC-33 V21 adds the table branding_logo: at most one logo per theme LIGHT or DARK, tenant_id "
            + "bigint not null, the PNG, its SHA-256, and a size within 512 by 128")
    void logoTable() {
        Map<String, String> logo = columns("branding_logo");

        assertThat(logo).as("columns of branding_logo").containsEntry("theme", "text not null")
                .containsEntry("tenant_id", "bigint not null")
                .containsEntry("png", "bytea not null")
                .containsEntry("sha256", "text not null")
                .containsEntry("width", "integer not null")
                .containsEntry("height", "integer not null")
                .containsEntry("changed_by", "bigint not null")
                .containsEntry("changed_at", "timestamp with time zone not null");
        List<String> checks = checks("branding_logo");
        assertThat(checks).as("check constraints of branding_logo")
                .anySatisfy(c -> assertThat(c).contains("theme").contains("'LIGHT'").contains("'DARK'"))
                .anySatisfy(c -> assertThat(plain(c)).contains("width>=1").contains("width<=512"))
                .anySatisfy(c -> assertThat(plain(c)).contains("height>=1").contains("height<=128"));
        assertThat(db.constraints("branding_logo").values()).as("constraints of branding_logo")
                .anySatisfy(c -> assertThat(plain(c)).isEqualTo("primarykeytheme"));
    }

    @Test
    @DisplayName("S9-AC-33 V21 puts in the default row: id 1, display name popdesk, no accent color, version 1")
    void defaultRow() {
        assertThat(db.tables()).as("tables").contains("branding", "branding_logo");
        Map<String, Object> row = db.row("select id, display_name, accent, version from branding");

        assertThat(((Number) row.get("id")).intValue()).as("id").isEqualTo(1);
        assertThat(row.get("display_name")).as("display_name").isEqualTo("popdesk");
        assertThat(row.get("accent")).as("accent").isNull();
        assertThat(((Number) row.get("version")).longValue()).as("version").isEqualTo(1);
        assertThat(db.count("select count(*) from branding_logo")).as("logos").isZero();
    }
}
