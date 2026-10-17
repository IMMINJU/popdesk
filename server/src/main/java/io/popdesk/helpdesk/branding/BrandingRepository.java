package io.popdesk.helpdesk.branding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The branding (tables {@code branding} and {@code branding_logo}, V21). The branding has one row, {@code id} 1,
 * which V21 put in; a write of the branding or of a logo locks it first ({@code FOR NO KEY UPDATE}), so writes of
 * both kinds take turns (slice 9 design 1절).
 */
@Repository
class BrandingRepository {

    /** The two themes as the table names them. */
    enum Theme { LIGHT, DARK }

    /** The display name, the accent color or null, and the version. */
    record Row(long version, String displayName, String accent) {}

    /** What is known of a stored logo without its PNG: the SHA-256 as lower-case hex, and its size. */
    record LogoInfo(String sha256, int width, int height) {}

    /** A stored logo's PNG and its SHA-256. */
    record LogoFile(byte[] png, String sha256) {}

    private static final String ROW = "select version, display_name, accent from branding where id = 1";

    private final JdbcClient jdbc;

    BrandingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Row find() {
        return jdbc.sql(ROW).query(BrandingRepository::row).single();
    }

    /** The row, locked until the end of the transaction. */
    Row lock() {
        return jdbc.sql(ROW + " for no key update").query(BrandingRepository::row).single();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("version"), rs.getString("display_name"), rs.getString("accent"));
    }

    void update(long version, String displayName, String accent, long changedBy, Instant changedAt) {
        jdbc.sql("update branding set version = ?, display_name = ?, accent = ?, changed_by = ?, changed_at = ? "
                        + "where id = 1")
                .params(version, displayName, accent, changedBy, Timestamp.from(changedAt))
                .update();
    }

    /** The logo of each theme that has one. */
    Map<Theme, LogoInfo> logos() {
        Map<Theme, LogoInfo> out = new EnumMap<>(Theme.class);
        jdbc.sql("select theme, sha256, width, height from branding_logo")
                .query(rs -> {
                    out.put(Theme.valueOf(rs.getString("theme")), new LogoInfo(rs.getString("sha256"),
                            rs.getInt("width"), rs.getInt("height")));
                });
        return out;
    }

    Optional<LogoFile> logo(Theme theme) {
        return jdbc.sql("select png, sha256 from branding_logo where theme = ?")
                .param(theme.name())
                .query((rs, n) -> new LogoFile(rs.getBytes("png"), rs.getString("sha256")))
                .optional();
    }

    /** The SHA-256 of the theme's logo, if it has one. */
    Optional<String> sha256(Theme theme) {
        return jdbc.sql("select sha256 from branding_logo where theme = ?").param(theme.name())
                .query(String.class).optional();
    }

    void putLogo(Theme theme, byte[] png, String sha256, int width, int height, long changedBy, Instant changedAt) {
        jdbc.sql("insert into branding_logo (theme, tenant_id, png, sha256, width, height, changed_by, changed_at) "
                        + "values (?, 1, ?, ?, ?, ?, ?, ?) on conflict (theme) do update set png = excluded.png, "
                        + "sha256 = excluded.sha256, width = excluded.width, height = excluded.height, "
                        + "changed_by = excluded.changed_by, changed_at = excluded.changed_at")
                .params(theme.name(), png, sha256, width, height, changedBy, Timestamp.from(changedAt))
                .update();
    }

    void deleteLogo(Theme theme) {
        jdbc.sql("delete from branding_logo where theme = ?").param(theme.name()).update();
    }
}
