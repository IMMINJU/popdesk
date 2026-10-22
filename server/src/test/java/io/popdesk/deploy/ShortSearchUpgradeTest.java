package io.popdesk.deploy;

import static io.popdesk.deploy.IssueInstallSteps.keptSample;
import static io.popdesk.deploy.IssueInstallSteps.sampleAdmin;
import static io.popdesk.deploy.IssueInstallSteps.startedOn;
import static io.popdesk.deploy.ShortSearchSteps.GRAM_INDEX;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Slice b8 on an installation of 0.5.x (B8-AC-06): the sample kept from 0.5.0 is restored and the image under test is
 * started on it. Its migration makes the gram function and the gram index; the function is immutable and works with an
 * empty {@code search_path}, as {@code pg_restore} runs it; and a search of one or two characters finds what 0.5.0
 * found. The stack is shared by the tests of this class.
 */
@Tag("deploy")
class ShortSearchUpgradeTest {

    private static final Once<Stack> UPGRADED = new Once<>("restoring the 0.5.0 sample and starting the image",
            () -> startedOn("grams050", keptSample("0.5.0", "sample.dump")));

    @AfterAll
    static void cleanUp() {
        Stack stack = UPGRADED.peek();
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("B8-AC-06 on the 0.5.0 sample this image finishes migrating, with a valid gram index whose functions "
            + "outside pg_catalog are all IMMUTABLE")
    void migrationMakesAnImmutableGramIndex() {
        Db db = UPGRADED.get().db();
        String schema = Db.quote(db.schema());
        assertThat(db.count("select count(*) from " + schema + ".flyway_schema_history where not success"))
                .as("failed migrations").isZero();

        ShortSearchSteps.assertGramIndexValid(db, "after the upgrade");

        List<Map<String, Object>> functions = db.query("select p.proname as name, p.provolatile::text as volatility "
                + "from pg_depend d join pg_proc p on p.oid = d.refobjid join pg_namespace n on n.oid = p.pronamespace "
                + "where d.classid = 'pg_class'::regclass and d.refclassid = 'pg_proc'::regclass "
                + "and d.objid = ?::regclass and n.nspname <> 'pg_catalog'", schema + "." + Db.quote(GRAM_INDEX));
        assertThat(functions).as("functions outside pg_catalog that %s uses", GRAM_INDEX).isNotEmpty()
                .allSatisfy(f -> assertThat(f.get("volatility")).as("provolatile of %s", f.get("name")).isEqualTo("i"));
    }

    @Test
    @DisplayName("B8-AC-06 on a connection with an empty search_path, the expression of the gram index finds the issues "
            + "of the 0.5.0 sample that hold a word of one or two letters, as ilike does")
    void gramExpressionWorksWithAnEmptySearchPath() throws SQLException {
        Db db = UPGRADED.get().db();
        String schema = db.schema();
        List<String> words = ShortSearchSteps.shortWords(db);
        assertThat(words).as("short words of the sample's titles").isNotEmpty();

        SoftAssertions softly = new SoftAssertions();
        try (Connection c = db.connect()) {
            Db.query(c, "set search_path = ''");
            List<Map<String, Object>> index = Db.query(c, "select pg_get_indexdef(x.indexrelid, 1, true) as expression "
                    + "from pg_index x join pg_catalog.pg_class i on i.oid = x.indexrelid "
                    + "join pg_catalog.pg_namespace n on n.oid = i.relnamespace where i.relname = ? and n.nspname = ?",
                    GRAM_INDEX, schema);
            assertThat(index).as("%s in pg_index", GRAM_INDEX).hasSize(1);
            String expression = (String) index.getFirst().get("expression");
            for (String word : words) {
                long byIndex = ((Number) Db.query(c, "select count(*) as n from " + Db.quote(schema)
                        + ".issue where deleted_at is null and (" + expression + ") @> array[?::text]", word)
                        .getFirst().get("n")).longValue();
                softly.assertThat(byIndex).as("issues whose %s holds %s, with an empty search_path", expression, word)
                        .isEqualTo(ShortSearchSteps.holding(db, word).size());
            }
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("B8-AC-06 after the upgrade from 0.5.0 the console and the portal find, with a word of one or two "
            + "letters, the issues 0.5.0 found with it")
    void shortSearchFindsWhat050Found() {
        Stack stack = UPGRADED.get();

        ShortSearchSteps.assertShortSearchAsBefore(stack, sampleAdmin(stack), stack.db());
    }
}
