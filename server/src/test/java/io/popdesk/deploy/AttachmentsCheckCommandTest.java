package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code popdesk attachments check} inside the app container ({@code design.md} 8절 of slice 5b): what
 * it prints, how it ends, and that it changes nothing. Each test starts an installation of its own
 * from the release Compose file and removes it.
 *
 * <p>The lines are fixed by the contract: {@code missing {storage} {id}}, {@code wrong-size {storage}
 * {id}}, one {@code {storage} {location}: checked n, missing n, wrong size n} per location that has
 * stored rows, and {@code no stored attachments}. Exit codes: 0, 2 when something is missing or of
 * another size, 1 when the command could not look.
 */
@Tag("deploy")
class AttachmentsCheckCommandTest {

    private static final String FOLDER = "/data/attachments";

    private Stack stack;

    @AfterEach
    void remove() {
        if (stack != null) {
            stack.close();
        }
    }

    private Stack started() {
        stack = Stack.release("check");
        stack.up();
        stack.awaitHealthy("app");
        return stack;
    }

    private static Cli.Result check(Stack stack, String... more) {
        List<String> command = new ArrayList<>(List.of("popdesk", "attachments", "check"));
        command.addAll(List.of(more));
        return stack.exec("app", null, command.toArray(String[]::new));
    }

    @Test
    @DisplayName("S5B-AC-40, S5B-AC-41, S5B-AC-42, S5B-AC-43 the command says no stored attachments on a new "
            + "installation (0); with files in db and on disk it prints one summary line per location (0); with "
            + "one file removed and one of another size it prints missing and wrong-size lines by attachment ID "
            + "and the counts (2), no file name anywhere, and rows and files are as before")
    void reportsWhatIsMissingAndChangesNothing() {
        Stack stack = started();

        Cli.Result none = check(stack);
        assertThat(none.exit()).as("exit code on an installation without attachments\n%s", none).isZero();
        assertThat(none.out().strip()).isEqualTo("no stored attachments");

        // One file in the database, then three on disk after the storage changed.
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        String company = steps.createCompany(admin);
        Person customer = steps.join(admin, "CUSTOMER", List.of(company), null);
        String issue = steps.fileIssue(customer, company);
        String nameToken = "named" + Unique.number() + "file";
        steps.attach(customer, issue, nameToken + "-db.bin", bytes(900));
        stack.appEnv("POPDESK_STORAGE", "disk");
        stack.up("app");
        stack.awaitHealthy("app");
        customer = portalSignIn(stack, customer);
        List<String> onDisk = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            onDisk.add(steps.attach(customer, issue, nameToken + "-" + i + ".bin", bytes(1_000 + i)));
        }
        onDisk.sort(null);
        String dbSummary = "db attachment_content: checked 1, missing 0, wrong size 0";

        Cli.Result whole = check(stack);
        assertThat(whole.exit()).as("exit code with every file in place\n%s", whole).isZero();
        assertThat(whole.out().strip().lines().toList()).containsExactly(dbSummary,
                "disk " + FOLDER + ": checked 3, missing 0, wrong size 0");

        // Remove one file, and put a file of another size in another one's place.
        String missing = onDisk.get(2);
        String wrongSize = onDisk.get(0);
        stack.exec("app", null, "rm", fileOf(missing)).expectOk("remove a file from the volume");
        stack.exec("app", null, "sh", "-c", "printf x > " + fileOf(wrongSize)).expectOk("shorten a file");
        Map<String, List<String>> rowsBefore = attachmentRows(stack);
        String filesBefore = files(stack);

        Cli.Result broken = check(stack);

        assertThat(broken.exit()).as("exit code with a missing file and one of another size\n%s", broken).isEqualTo(2);
        assertThat(broken.out().strip().lines().toList()).containsExactly(
                "wrong-size disk " + wrongSize,
                "missing disk " + missing,
                dbSummary,
                "disk " + FOLDER + ": checked 3, missing 1, wrong size 1");
        for (Cli.Result result : List.of(none, whole, broken)) {
            assertThat(result.out() + result.err()).as("what the command printed").doesNotContain(nameToken);
        }
        assertThat(attachmentRows(stack)).as("every row of the attachment tables after the command")
                .isEqualTo(rowsBefore);
        assertThat(files(stack)).as("files of the volume, with their sizes, after the command").isEqualTo(filesBefore);
        assertThat(stack.inspect("app").path("State").path("Running").asBoolean(false)).as("the app still runs")
                .isTrue();
    }

    @Test
    @DisplayName("S5B-AC-42 when the command cannot reach the database it ends with 1 and one reason on standard "
            + "error that starts with popdesk: , and prints no result")
    void endsWithOneWhenTheDatabaseIsOutOfReach() {
        Stack stack = started();

        Cli.Result result = stack.command("exec", "-T", "-e",
                "POPDESK_DB_URL=jdbc:postgresql://db:1/popdesk?connectTimeout=5", "app", "popdesk", "attachments",
                "check");

        assertThat(result.exit()).as("exit code\n%s", result).isEqualTo(1);
        assertThat(result.err().strip().lines().toList()).as("standard error").hasSize(1)
                .allMatch(line -> line.startsWith("popdesk: "));
        assertThat(result.err()).as("the reason is about the database, not about how to call the command")
                .doesNotContain("unknown command").doesNotContain("Usage");
        assertThat(result.out().strip()).as("standard output").isEmpty();
    }

    @Test
    @DisplayName("S5B-AC-42 with an argument after check the command ends with 1 and prints how to call it, with "
            + "the line for popdesk attachments check")
    void anotherArgumentIsAUsageError() {
        Stack stack = started();

        Cli.Result result = check(stack, "--fix");

        assertThat(result.exit()).as("exit code\n%s", result).isEqualTo(1);
        assertThat(result.err()).as("standard error").startsWith("popdesk: ").contains("popdesk attachments check");
        assertThat(result.out().strip()).as("standard output").isEmpty();
    }

    /** Every row of the tables whose name starts with {@code attachment}: the rows and the contents in the database. */
    private static Map<String, List<String>> attachmentRows(Stack stack) {
        Map<String, List<String>> rows = new TreeMap<>(stack.db().snapshot());
        rows.keySet().removeIf(table -> !table.startsWith("attachment"));
        assertThat(rows).as("tables whose name starts with attachment").isNotEmpty();
        return rows;
    }

    private static String fileOf(String id) {
        return FOLDER + "/" + id.substring(0, 2) + "/" + id;
    }

    /** Every file of the attachment volume with its size, sorted. */
    private static String files(Stack stack) {
        return stack.exec("app", null, "sh", "-c", "find " + FOLDER + " -type f -exec stat -c '%n %s' {} + | sort")
                .expectOk("list the attachment volume").out();
    }

    /** The same customer signed in again on the portal, after the app was started again. */
    private static Person portalSignIn(Stack stack, Person customer) {
        ApiClient client = stack.portal();
        client.post("/api/public/sign-in").json(Map.of("email", customer.email(), "password", customer.password()))
                .expect(204);
        client.get("/api/portal/me").expect(200);
        return new Person(client, "/api/portal", customer.accountId(), customer.email(), customer.password());
    }

    private static byte[] bytes(int size) {
        byte[] out = new byte[size];
        new Random(Unique.number()).nextBytes(out);
        return out;
    }
}
