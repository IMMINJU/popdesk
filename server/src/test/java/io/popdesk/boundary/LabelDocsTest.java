package io.popdesk.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The written rules of slice 6a agree with what the tests check ({@code acceptance.md} S6A-AC-92): the console
 * contract marks the three label writes, and nothing else of the slice, as for admins only, and the rows of
 * {@code docs/use/permissions.md} about labels and due dates say who may do what. No app is started; the
 * behavior itself is checked by {@code label.LabelManageTest}, {@code boundary.LabelBoundaryTest}, and
 * {@code AdminOnlyContractTest}.
 */
class LabelDocsTest {

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        }
    }

    /** The cells of the table row whose first cell is {@code action}; fails unless there is exactly one. */
    private static List<String> row(String markdown, String action) {
        List<List<String>> rows = markdown.lines().filter(l -> l.startsWith("|"))
                .map(l -> Arrays.stream(l.substring(1, l.lastIndexOf('|')).split("\\|")).map(String::strip).toList())
                .filter(cells -> cells.getFirst().equals(action)).toList();
        assertThat(rows).as("rows of permissions.md for '%s'", action).hasSize(1);
        return rows.getFirst();
    }

    @Test
    @DisplayName("S6A-AC-92 the console contract marks creating, editing, and deleting a label as for admins only, and the list, adding, removing, and the due date as not")
    void contractMarksTheLabelWritesAdminsOnly() {
        JsonNode paths = JsonMapper.builder().build()
                .readTree(read(Path.of("..", "docs", "integrate", "openapi-console.json"))).path("paths");
        Map<String, Boolean> adminOnly = new TreeMap<>();
        for (String path : paths.propertyNames()) {
            if (path.contains("/labels") || path.endsWith("/due-date")) {
                for (String method : paths.path(path).propertyNames()) {
                    String forbidden = paths.path(path).path(method).path("responses").path("403")
                            .path("description").asString("");
                    adminOnly.put(method.toUpperCase(Locale.ROOT) + " " + path,
                            forbidden.toLowerCase(Locale.ROOT).contains("admins only"));
                }
            }
        }

        assertThat(adminOnly).as("operations of slice 6a and whether their 403 says admins only").isEqualTo(Map.of(
                "GET /api/console/companies/{code}/labels", false,
                "POST /api/console/companies/{code}/labels", true,
                "PATCH /api/console/companies/{code}/labels/{id}", true,
                "DELETE /api/console/companies/{code}/labels/{id}", true,
                "PUT /api/console/issues/{key}/labels/{id}", false,
                "DELETE /api/console/issues/{key}/labels/{id}", false,
                "PUT /api/console/issues/{key}/due-date", false,
                "DELETE /api/console/issues/{key}/due-date", false));
    }

    @Test
    @DisplayName("S6A-AC-92 permissions.md says agents list the labels of companies assigned to them, only admins manage labels with at most 200 and none new in an ARCHIVED company, staff label issues up to 20 and set due dates, and customers do none of it")
    void permissionsRowsMatchTheBehavior() {
        String doc = read(Path.of("..", "docs", "use", "permissions.md"));

        assertThat(row(doc, "List a company's labels")).as("the list row")
                .containsExactly("List a company's labels", "–", "Assigned to them", "All");
        List<String> manage = row(doc, "Create, edit, delete labels");
        assertThat(manage.subList(0, 3)).as("the manage row: customer and agent")
                .containsExactly("Create, edit, delete labels", "–", "–");
        assertThat(manage.get(3)).as("the manage row: admin").startsWith("Yes").contains("200")
                .contains("`ARCHIVED`").contains("edited and deleted");
        List<String> onIssue = doc.lines().filter(l -> l.startsWith("| Add or remove labels")).toList();
        assertThat(onIssue).as("the row about labels and due dates on an issue").hasSize(1);
        assertThat(onIssue.getFirst()).contains("at most 20").contains("`POPDESK_TIMEZONE`")
                .endsWith("| – | – | Yes | Yes |");
        assertThat(row(doc, "Filter the list by label, sort it by due date")).as("the filter row")
                .containsExactly("Filter the list by label, sort it by due date", "–", "–", "Yes", "Yes");
        assertThat(doc).as("what a CLOSED issue refuses")
                .contains("On a `CLOSED` issue nothing can be edited, commented on, attached, labelled, or given a due date (400)");
        assertThat(doc).as("what customers never see").contains("assignee, labels, due dates");
    }
}
