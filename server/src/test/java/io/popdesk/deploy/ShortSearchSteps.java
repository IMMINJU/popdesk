package io.popdesk.deploy;

import static io.popdesk.deploy.IssueInstallSteps.SAMPLE_CUSTOMER;
import static io.popdesk.deploy.IssueInstallSteps.SAMPLE_PASSWORD;
import static io.popdesk.deploy.IssueInstallSteps.keys;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;

/**
 * Steps the deploy tests of slice b8 share ({@link ShortSearchUpgradeTest}, {@link ShortSearchRestoreTest}): the gram
 * index as the database has it, and searches of one or two characters on an installation started on a kept sample.
 *
 * <p>What a search should find is worked out from {@code issue.search_text} as the version that made the sample
 * stored it, with the {@code ilike} that version searched with. The words are letters only, so no character of them
 * is special to {@code ilike}.
 */
final class ShortSearchSteps {

    static final String GRAM_INDEX = "issue_search_grams_idx";

    private ShortSearchSteps() {}

    /** {@code pg_index} rows of the gram index in popdesk's schema: whether it is valid, and its expression. */
    static List<Map<String, Object>> gramIndex(Db db) {
        return db.query("select x.indisvalid as valid, pg_get_indexdef(x.indexrelid, 1, true) as expression "
                + "from pg_index x join pg_class c on c.oid = x.indexrelid join pg_namespace n on n.oid = c.relnamespace "
                + "where c.relname = ? and n.nspname = ?", GRAM_INDEX, db.schema());
    }

    /** Fails unless popdesk's schema has the gram index once, and it is valid. */
    static void assertGramIndexValid(Db db, String when) {
        List<Map<String, Object>> rows = gramIndex(db);
        assertThat(rows).as("%s in pg_index %s", GRAM_INDEX, when).hasSize(1);
        assertThat(rows.getFirst().get("valid")).as("indisvalid of %s %s", GRAM_INDEX, when).isEqualTo(true);
    }

    /**
     * Words of one and two letters taken from the titles of the issues in the list: the first letter of each title and
     * its first two when both are letters, in lower case, without repeats.
     */
    static List<String> shortWords(Db db) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : db.query("select title from " + Db.quote(db.schema())
                + ".issue where deleted_at is null order by created_at")) {
            String title = ((String) row.get("title")).toLowerCase(Locale.ROOT);
            int[] letters = title.codePoints().limit(2).toArray();
            if (letters.length >= 1 && Character.isLetter(letters[0])) {
                out.add(new String(letters, 0, 1));
                if (letters.length == 2 && Character.isLetter(letters[1])) {
                    out.add(new String(letters, 0, 2));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Keys of the issues not in the trash whose stored search text holds {@code word} by {@code ilike}. */
    static List<String> holding(Db db, String word) {
        return db.query("select c.code || '-' || i.number as key from " + Db.quote(db.schema()) + ".issue i join "
                        + Db.quote(db.schema()) + ".company c on c.id = i.company_id "
                        + "where i.deleted_at is null and i.search_text ilike '%' || ? || '%'", word)
                .stream().map(r -> (String) r.get("key")).toList();
    }

    /**
     * Fails unless the console (as the sample's admin) and the portal (as the sample's customer) find, for every short
     * word of the sample's titles and for one of them in capitals, the issues whose stored search text holds it, and
     * count them; and unless some word leaves an issue out.
     */
    static void assertShortSearchAsBefore(Stack stack, Person admin, Db db) {
        List<String> words = new ArrayList<>(shortWords(db));
        assertThat(words).as("short words of the sample's titles").isNotEmpty();
        words.add(words.stream().filter(w -> w.codePointCount(0, w.length()) == 2).findFirst().orElse(words.getFirst())
                .toUpperCase(Locale.ROOT));
        ApiClient portal = stack.portal();
        portal.post("/api/public/sign-in").json(Map.of("email", SAMPLE_CUSTOMER, "password", SAMPLE_PASSWORD))
                .expect(204);
        List<String> seenOnPortal = keys(portal.get("/api/portal/issues").query("size", 100).expect(200));
        List<String> all = keys(admin.client().get("/api/console/issues").query("size", 100).expect(200));
        int narrowed = 0;

        SoftAssertions softly = new SoftAssertions();
        for (String word : words) {
            List<String> holding = holding(db, word);
            List<String> holdingOnPortal = holding.stream().filter(seenOnPortal::contains).toList();
            ApiResponse console = admin.client().get("/api/console/issues").query("q", word).query("size", 100)
                    .expect(200);
            ApiResponse onPortal = portal.get("/api/portal/issues").query("q", word).query("size", 100).expect(200);

            softly.assertThat(keys(console)).as("issues the console finds with q=%s", word)
                    .containsExactlyInAnyOrderElementsOf(holding);
            softly.assertThat(console.json().path("totalElements").asLong(-1))
                    .as("totalElements on the console with q=%s", word).isEqualTo(holding.size());
            softly.assertThat(keys(onPortal)).as("issues the portal finds with q=%s", word)
                    .containsExactlyInAnyOrderElementsOf(holdingOnPortal);
            softly.assertThat(onPortal.json().path("totalElements").asLong(-1))
                    .as("totalElements on the portal with q=%s", word).isEqualTo(holdingOnPortal.size());
            if (holding.size() < all.size()) {
                narrowed++;
            }
        }
        softly.assertThat(narrowed).as("short words that leave some issue of the sample out").isPositive();
        softly.assertAll();
    }
}
