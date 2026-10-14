package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

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

    private static final Set<String> FILE_STATES = Set.of("pending", "stored", "purging", "purged");

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
        Map<String, Long> auditBefore = auditRows(stack);
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
        assertThat(auditRows(stack)).as("rows per audit log table after the command: it leaves no audit entry")
                .isEqualTo(auditBefore);
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

    @Test
    @DisplayName("S5B-AC-40, S5B-AC-41, S5B-AC-42, S5B-AC-25 with files in s3 the command counts stored rows only, "
            + "not a pending, a purging, or a purged one (0); with the object of a stored row gone it prints "
            + "missing s3 and the attachment ID and the summary line of {bucket}/popdesk (2); when it cannot reach "
            + "the storage, or S3_SECRET_KEY is not set, it ends with 1 and a reason on standard error; no output "
            + "holds a file name or a key's value")
    void countsStoredRowsInS3AndEndsWithOneWithoutTheStorage() {
        String accessKey = "pdcheck" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String secretKey = "pdcheck" + UUID.randomUUID().toString().replace("-", "");
        String bucket = "pd-check-" + UUID.randomUUID().toString().substring(0, 8);
        stack = Stack.release("checks3").withResource("storage-s3.compose.yml")
                .env("POPDESK_TEST_S3_ACCESS", accessKey).env("POPDESK_TEST_S3_SECRET", secretKey);
        stack.up("db", "s3");
        awaitS3(stack, accessKey, secretKey, s3 -> s3.createBucket(b -> b.bucket(bucket)));
        stack.appEnv("POPDESK_STORAGE", "s3")
                .appEnv("S3_ENDPOINT", "http://s3:7070")
                .appEnv("S3_BUCKET", bucket)
                .appEnv("S3_ACCESS_KEY", accessKey)
                .appEnv("S3_SECRET_KEY", secretKey);
        stack.up();
        stack.awaitHealthy("app", Duration.ofMinutes(2));

        // Two stored files and a purged one; then, with the storage stopped, a pending and a purging one.
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        String company = steps.createCompany(admin);
        Person customer = steps.join(admin, "CUSTOMER", List.of(company), null);
        String issue = steps.fileIssue(customer, company);
        String nameToken = "named" + Unique.number() + "file";
        List<String> stored = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            stored.add(steps.attach(customer, issue, nameToken + "-stored-" + i + ".bin", bytes(1_000 + i)));
        }
        stored.sort(null);
        String purged = steps.attach(customer, issue, nameToken + "-purged.bin", bytes(700));
        delete(customer, issue, purged).expectStatus(204);
        String purging = steps.attach(customer, issue, nameToken + "-purging.bin", bytes(800));
        stack.command("stop", "s3").expectOk("stop the S3 stand-in");
        String pendingName = nameToken + "-pending.bin";
        ApiResponse notWritten = customer.client().post(customer.api() + "/issues/" + issue + "/attachments")
                .file("file", pendingName, "application/octet-stream", bytes(900)).send();
        assertThat(notWritten.status()).as("%s %s", notWritten, notWritten.snippet()).isEqualTo(500);
        delete(customer, issue, purging).expectStatus(204);
        stack.command("start", "s3").expectOk("start the S3 stand-in again");
        awaitS3(stack, accessKey, secretKey, s3 -> s3.headBucket(b -> b.bucket(bucket)));
        assertThat(fileState(stack, purged)).as("state of the deleted file's row").isEqualTo("purged");
        assertThat(fileState(stack, purging)).as("state of the row whose removal failed").isEqualTo("purging");
        assertThat(fileState(stack, pendingName)).as("state of the row whose write failed").isEqualTo("pending");
        for (String id : stored) {
            assertThat(fileState(stack, id)).as("state of %s", id).isEqualTo("stored");
        }
        String place = "s3 " + bucket + "/popdesk";

        Cli.Result whole = check(stack);
        assertThat(whole.exit()).as("exit code with every stored file in place\n%s", whole).isZero();
        assertThat(whole.out().strip().lines().toList())
                .containsExactly(place + ": checked 2, missing 0, wrong size 0");

        String missing = stored.get(1);
        awaitS3(stack, accessKey, secretKey, s3 -> s3.deleteObject(b -> b.bucket(bucket).key("popdesk/" + missing)));
        Cli.Result broken = check(stack);
        assertThat(broken.exit()).as("exit code with the object of a stored row gone\n%s", broken).isEqualTo(2);
        assertThat(broken.out().strip().lines().toList()).containsExactly("missing s3 " + missing,
                place + ": checked 2, missing 1, wrong size 0");

        Cli.Result unreachable = stack.command("exec", "-T", "-e", "S3_ENDPOINT=http://s3:1", "app", "popdesk",
                "attachments", "check");
        Cli.Result noSecret = stack.exec("app", null, "sh", "-c",
                "unset S3_SECRET_KEY; exec popdesk attachments check");
        for (Cli.Result result : List.of(unreachable, noSecret)) {
            assertThat(result.exit()).as("exit code\n%s", result).isEqualTo(1);
            assertThat(result.err().strip().lines().toList()).as("standard error\n%s", result)
                    .anyMatch(line -> line.startsWith("popdesk: "));
            assertThat(result.err()).as("the reason is not about how to call the command")
                    .doesNotContain("unknown command").doesNotContain("Usage");
            // A file that could not be looked at is not a file that is missing.
            assertThat(result.out().lines().toList()).as("standard output\n%s", result)
                    .noneMatch(line -> line.startsWith("missing "));
        }
        for (Cli.Result result : List.of(whole, broken, unreachable, noSecret)) {
            assertThat(result.out() + result.err()).as("what the command printed").doesNotContain(nameToken)
                    .doesNotContain(secretKey).doesNotContain(accessKey);
        }
    }

    /**
     * Does {@code request} on the S3 stand-in through its published port, again until it takes the
     * request (the port changes when the stand-in is started again).
     */
    private static void awaitS3(Stack stack, String accessKey, String secretKey, Consumer<S3Client> request) {
        Wait.until("the S3 stand-in takes the request", Duration.ofMinutes(1), () -> {
            try (S3Client s3 = S3Client.builder()
                    .endpointOverride(URI.create("http://127.0.0.1:" + stack.port("s3", 7070)))
                    .region(Region.US_EAST_1).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .httpClient(UrlConnectionHttpClient.create()).build()) {
                request.accept(s3);
                return stack;
            } catch (SdkException notYet) {
                return null;
            }
        });
    }

    private static ApiResponse delete(Person person, String issue, String id) {
        return person.client().delete(person.api() + "/issues/" + issue + "/attachments/" + id).send();
    }

    /**
     * The file state of the one row that holds {@code needle} (an attachment ID, or a file name no
     * other row has) and a {@code state} of a file.
     */
    private static String fileState(Stack stack, String needle) {
        List<String> states = stack.db().rowsContaining(needle).stream().map(row -> row.json().path("state"))
                .filter(JsonNode::isString).map(JsonNode::stringValue).filter(FILE_STATES::contains).toList();
        assertThat(states).as("file states of the rows that hold %s", needle).hasSize(1);
        return states.getFirst();
    }

    /** Rows per table whose name starts with {@code audit}: the audit log and its chain. */
    private static Map<String, Long> auditRows(Stack stack) {
        Map<String, Long> rows = new TreeMap<>(stack.db().counts());
        rows.keySet().removeIf(table -> !table.startsWith("audit"));
        assertThat(rows).as("tables whose name starts with audit").isNotEmpty();
        assertThat(rows.values().stream().mapToLong(Long::longValue).sum()).as("audit rows of the installation")
                .isPositive();
        return rows;
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
