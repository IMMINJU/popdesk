package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps;
import io.popdesk.start.Database.Table;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the tests of slice 5b (attachment storage {@code disk} and {@code s3}) share: the setting keys
 * of each storage, people and attachment requests on an app started with {@link Boot}, the attachment
 * row of a file, a look into the folder and the bucket, and the S3 stand-in.
 *
 * <p>The names come from {@code design/archive/5b-storage/design.md} section 8: the marker
 * {@value #MARKER}, a file at {@code {folder}/{first two characters of the ID}/{ID}}, an object at
 * {@code popdesk/{ID}}, and the row's location {@code {bucket}/popdesk}.
 *
 * <p>A test writes to a storage only to remove a file, remove the marker, or put a file of another
 * size in a file's place.
 */
final class StorageSteps {

    static final String MARKER = ".popdesk-storage";

    /** The prefix of every object key, and the end of an {@code s3} row's location. */
    static final String PREFIX = "popdesk";

    static final int MB = 1024 * 1024;

    static final Set<String> STATES = Set.of("pending", "stored", "purging", "purged");

    /** The table of file contents for {@code POPDESK_STORAGE=db}; never read as JSON here (rows are large). */
    static final String CONTENT_TABLE = "attachment_content";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StorageSteps() {}

    // Settings

    /** A first install that keeps new files in {@code folder}. */
    static Settings disk(Database db, Path folder) {
        return Settings.firstInstall(db).set("POPDESK_STORAGE", "disk").set("POPDESK_STORAGE_DIR", folder.toString());
    }

    /** A first install that keeps new files in {@code bucket} of the stand-in, reached through its gate. */
    static Settings s3(Database db, S3 s3, String bucket) {
        return Settings.firstInstall(db).set("POPDESK_STORAGE", "s3")
                .set("S3_ENDPOINT", s3.endpoint())
                .set("S3_BUCKET", bucket)
                .set("S3_ACCESS_KEY", s3.accessKey())
                .set("S3_SECRET_KEY", s3.secretKey());
    }

    /** The S3 keys an installation keeps when its storage is no longer {@code s3} but old rows are. */
    static Settings withS3Keys(Settings settings, S3 s3, String bucket) {
        return settings.set("S3_ENDPOINT", s3.endpoint()).set("S3_BUCKET", bucket)
                .set("S3_ACCESS_KEY", s3.accessKey()).set("S3_SECRET_KEY", s3.secretKey());
    }

    /**
     * Gives the app a {@link TestClock} as its {@code Clock} ({@link StorageClock}), through
     * {@code spring.main.sources}; read it with {@link #clock}. For the cleanup jobs, which run by
     * the app's clock.
     */
    static Settings withTestClock(Settings settings) {
        return settings.set("spring.main.sources", StorageClock.class.getName());
    }

    static TestClock clock(Boot app) {
        return app.context().getBean(TestClock.class);
    }

    /** A new empty folder under a new temporary folder, as a real path (no links, no short names). */
    static Path newFolder(String name) {
        try {
            Path base = Files.createTempDirectory("popdesk-storage-").toRealPath();
            return Files.createDirectories(base.resolve("outer").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes a folder {@link #newFolder} made, with the temporary folder around it. */
    static void removeFolder(Path folder) {
        Path base = folder.getParent().getParent();
        try (Stream<Path> paths = Files.walk(base)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A leftover temporary folder changes no result.
        }
    }

    // Something slow that the tests of a class share

    /**
     * An app (or a stand-in) that the tests of a class share, made by the first test that asks. When
     * making it fails, every test fails with an {@link AssertionError}, not with a lifecycle error.
     */
    static final class Shared<T> {

        private final String what;
        private final Supplier<T> make;
        private T value;
        private AssertionError failure;

        Shared(String what, Supplier<T> make) {
            this.what = what;
            this.make = make;
        }

        synchronized T get() {
            if (failure != null) {
                throw new AssertionError("Earlier, " + what + " failed: " + failure.getMessage(), failure);
            }
            if (value == null) {
                try {
                    value = make.get();
                } catch (AssertionError e) {
                    failure = e;
                    throw e;
                } catch (RuntimeException e) {
                    failure = new AssertionError(what + " failed: " + e, e);
                    throw failure;
                }
            }
            return value;
        }

        /** The value when it was made, else null. */
        synchronized T peek() {
            return value;
        }
    }

    // People, on an app started with Boot

    /** The first admin, a company, and a signed-in customer of it. */
    record Scene(Boot app, Session admin, String code, Session customer) {}

    static Scene scene(Boot app) {
        Session admin = signIn(app, App.CONSOLE, Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
        String code = Unique.companyCode();
        admin.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", "Company " + code, "legalName", "Company " + code + " Ltd."))
                .expect(201);
        return new Scene(app, admin, code, customer(app, admin, code));
    }

    static Session signIn(Boot app, App where, String email, String password) {
        ApiClient client = where == App.PORTAL ? app.portal() : app.console();
        client.post("/api/public/sign-in").json(Map.of("email", email, "password", password)).expect(204);
        ApiResponse me = client.get(where.api() + "/me").expect(200);
        Role role = where == App.PORTAL ? Role.CUSTOMER : Role.valueOf(me.jsonText("role"));
        return new Session(client, where, me.jsonLong("id"), me.jsonText("email"), me.jsonText("name"), password,
                role);
    }

    /** The same person signed in on another start of the app, or after their session ended. */
    static Session again(Boot app, Session session) {
        return signIn(app, session.app(), session.email(), session.password());
    }

    /** Invites a customer of the company and accepts with the link in the answer (no SMTP is set). */
    static Session customer(Boot app, Session admin, String code) {
        String email = Unique.email("customer");
        String name = Unique.text("customer");
        ApiResponse invited = admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", name, "role", "CUSTOMER", "companyCodes", List.of(code)))
                .expect(201);
        ApiClient client = app.portal();
        client.post("/api/public/invitations/accept")
                .json(Map.of("token", Fixtures.tokenOf(invited.jsonText("inviteLink")), "password", Fixtures.PASSWORD))
                .expect(204);
        return new Session(client, App.PORTAL, invited.jsonLong("accountId"), email, name, Fixtures.PASSWORD,
                Role.CUSTOMER);
    }

    /** Files a public issue on the portal and gives its key. */
    static String fileIssue(Session customer, String code) {
        return customer.client().post("/api/portal/issues")
                .json(Map.of("companyCode", code, "title", Unique.text("Issue"), "content", "Steps to reproduce."))
                .expect(201).jsonText("key");
    }

    /** A public comment of the caller; its ID. */
    static long comment(Session session, String key) {
        return session.client().post(session.api("/issues/" + key + "/comments"))
                .json(Map.of("content", Unique.text("Comment"))).expect(201).jsonLong("id");
    }

    // Attachment requests

    static ApiResponse upload(Session session, String key, String fileName, byte[] content) {
        return session.client().post(session.api("/issues/" + key + "/attachments"))
                .file("file", fileName, "application/octet-stream", content).send();
    }

    /** Uploads and gives the attachment ID; fails with an AssertionError unless the answer is 201. */
    static String uploaded(Session session, String key, String fileName, byte[] content) {
        return upload(session, key, fileName, content).expectStatus(201).jsonText("id");
    }

    static String path(Session session, String key, String id) {
        return session.api("/issues/" + key + "/attachments/" + id);
    }

    static ApiResponse download(Session session, String key, String id) {
        return session.client().get(path(session, key, id)).send();
    }

    static ApiResponse delete(Session session, String key, String id) {
        return session.client().delete(path(session, key, id)).send();
    }

    /** Every attachment the caller sees on the issue (one page of 100). */
    static List<JsonNode> list(Session session, String key) {
        ApiResponse page = session.client().get(session.api("/issues/" + key + "/attachments")).query("size", 100)
                .expect(200);
        JsonNode items = page.json().path("items");
        if (!items.isArray()) {
            throw new AssertionError(page.request() + ": no items array in " + page.snippet());
        }
        return new ArrayList<>(items.values());
    }

    static List<String> ids(List<JsonNode> items) {
        return items.stream().map(n -> n.path("id").asString(null)).toList();
    }

    /** The list entry with this ID, or null. */
    static JsonNode entry(Session session, String key, String id) {
        return list(session, key).stream().filter(n -> id.equals(n.path("id").asString(null))).findFirst()
                .orElse(null);
    }

    /**
     * An upload sent with a time limit of its own ({@code ApiRequest} gives up after 60 seconds, which
     * is also how long the app may wait for a storage). Gives the status and the body, and fails with
     * an AssertionError when no answer came within {@code atMost}.
     */
    static HttpResponse<String> uploadWithin(Duration atMost, Session session, String key, String fileName,
            byte[] content) {
        ApiClient client = session.client();
        String boundary = "popdesk-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        String cookies = client.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.port()
                        + session.api("/issues/" + key + "/attachments")))
                .timeout(atMost)
                .header("Host", client.host())
                .header(ApiClient.FORWARDED_FOR, client.forwardedFor())
                .header("Cookie", cookies)
                .header(ApiClient.CSRF_HEADER, client.csrfToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("The upload of " + fileName + " got no answer within " + atMost + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while uploading " + fileName, e);
        }
    }

    // Content

    /** {@code size} bytes that are the same for the same seed. */
    static byte[] bytes(int size, long seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
    }

    /** A few hundred bytes no other test has. */
    static byte[] small() {
        return bytes(300, Unique.number());
    }

    static byte[] slice(byte[] content, int from, int toInclusive) {
        return Arrays.copyOfRange(content, from, toInclusive + 1);
    }

    static String sha256(byte[] content) {
        return ImageSteps.sha256(content);
    }

    // The attachment row

    /**
     * The row of an attachment: the one row, in any table but {@value #CONTENT_TABLE}, with a column
     * equal to the ID and a column holding a file state. Fails with an AssertionError otherwise.
     */
    static JsonNode row(Database db, String id) {
        List<JsonNode> found = rows(db, id);
        if (found.size() != 1) {
            throw new AssertionError("Expected one attachment row with ID " + id + " and a file state, found: "
                    + found);
        }
        return found.getFirst();
    }

    /** The rows that {@link #row} would take for the attachment's: none once the row was removed. */
    static List<JsonNode> rows(Database db, String id) {
        if (id == null || !ImageSteps.UUID_FORM.matcher(id).matches()) {
            throw new AssertionError("Not an attachment ID: " + id);
        }
        List<JsonNode> found = new ArrayList<>();
        for (Table table : db.tables()) {
            if (table.name().equals(CONTENT_TABLE)) {
                continue;
            }
            for (String text : db.query("select row_to_json(t)::text from " + table.qualified()
                    + " t where position('" + id + "' in row_to_json(t)::text) > 0", rs -> rs.getString(1))) {
                JsonNode row = JSON.readTree(text);
                boolean hasId = false;
                boolean hasState = false;
                for (JsonNode value : row.values()) {
                    hasId |= value.isString() && value.stringValue().equals(id);
                    hasState |= value.isString() && STATES.contains(value.stringValue());
                }
                if (hasId && hasState) {
                    found.add(row);
                }
            }
        }
        return found;
    }

    /** {@code pending}, {@code stored}, {@code purging}, or {@code purged}. */
    static String state(Database db, String id) {
        return row(db, id).path("state").asString(null);
    }

    /** The row's storage kind: {@code db}, {@code disk}, or {@code s3}. */
    static String storage(Database db, String id) {
        return row(db, id).path("storage").asString(null);
    }

    /** The row's {@code storage_location}. */
    static String location(Database db, String id) {
        return row(db, id).path("storage_location").asString(null);
    }

    /** How many rows of {@value #CONTENT_TABLE} belong to this attachment (0 or 1). */
    static long contentRows(Database db, String id) {
        Table table = db.table(CONTENT_TABLE).orElseThrow(() -> new AssertionError("No table " + CONTENT_TABLE));
        return db.query("select count(*) from " + table.qualified() + " where attachment_id = '"
                + UUID.fromString(id) + "'", rs -> rs.getLong(1)).getFirst();
    }

    // The folder

    /** Where {@code disk} keeps the file of an attachment. */
    static Path fileOf(Path folder, String id) {
        return folder.resolve(id.substring(0, 2)).resolve(id);
    }

    static Path marker(Path folder) {
        return folder.resolve(MARKER);
    }

    /** Every file under {@code folder}, as paths from it with {@code /}. Empty when the folder is missing. */
    static List<String> filesUnder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile)
                    .map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void remove(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new AssertionError("Could not remove " + file + ": " + e, e);
        }
    }

    // The bucket

    static String keyOf(String id) {
        return PREFIX + "/" + id;
    }

    static String markerKey() {
        return PREFIX + "/" + MARKER;
    }

    /** An {@code s3} row's location for a bucket. */
    static String locationOf(String bucket) {
        return bucket + "/" + PREFIX;
    }

    // The log of a start

    /**
     * What a start told: its log and, for a start that failed, the messages of the failure and its
     * causes (Spring logs them too; a test must find a secret in neither).
     */
    static String told(Boot app) {
        StringBuilder out = new StringBuilder(app.log());
        for (Throwable t = app.failure(); t != null; t = t.getCause() == t ? null : t.getCause()) {
            out.append('\n').append(t.getClass().getName()).append(": ").append(t.getMessage());
        }
        return out.toString();
    }

    /** The lines of {@link #told} that hold {@code text}, written plainly or as a JSON log line escapes it. */
    static List<String> linesWith(Boot app, String text) {
        String escaped = text.replace("\\", "\\\\");
        return told(app).lines().filter(line -> line.contains(text) || line.contains(escaped)).toList();
    }

    /** Whether {@code line} holds the number {@code n} on its own (not as part of a longer number), outside {@code without}. */
    static boolean holdsNumber(String line, long n, String without) {
        String rest = line.replace(without, " ").replace(without.replace("\\", "\\\\"), " ");
        return Pattern.compile("(?<![0-9])" + n + "(?![0-9])").matcher(rest).find();
    }

    /**
     * A secret is in a text neither as it is nor percent-encoded. Fails first when the text is too
     * short to be a log at all, so that "not found" is never said of an empty capture.
     */
    static void assertHoldsNoSecret(String text, String what, String... secrets) {
        assertThat(text.lines().count()).as("lines of %s", what).isGreaterThan(3);
        for (String secret : secrets) {
            assertThat(text).as("%s holds a secret value", what).doesNotContain(secret)
                    .doesNotContain(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
    }

    /**
     * What an exception of the S3 client says, as the stand-in and SDK 2.55.15 gave it on this machine
     * (2026-10-14): the end of every service error ({@code (Service: S3, Status Code: 403, Request ID:
     * ..., Extended Request ID: ...) (SDK Attempt Count: 1)}), the start of every error without an
     * answer ({@code Unable to execute HTTP request: ...}), the server's sentences, and a stack frame
     * of the SDK. Not here, because {@code design.md} 2절 lets the app write them: the storage kind,
     * the place, a status code, an S3 error code such as {@code NoSuchBucket}, and a class name.
     */
    static final List<String> SDK_TEXT = List.of("Service: S3", "Request ID", "SDK Attempt Count",
            "Unable to execute HTTP request", "The request signature we calculated",
            "The specified bucket does not exist", "The specified key does not exist",
            "at software.amazon.awssdk.");

    /**
     * No text of an S3 client exception is in {@code text} (S5B-AC-25, {@code design.md} 9절 5): the
     * app neither logs such an exception nor passes it on as a cause. Fails first when the text is
     * too short to be a log or an answer at all.
     */
    static void assertHoldsNoSdkText(String text, String what) {
        assertThat(text).as(what).isNotBlank();
        for (String told : SDK_TEXT) {
            assertThat(text).as("%s holds the text of an S3 client exception", what).doesNotContain(told);
        }
    }

    // The S3 stand-in

    /**
     * versitygw ({@code design.md} 0절 11), a real S3-compatible server that checks signatures, with
     * one root key pair made for this JVM, and a {@link Gate} in front of it that the app's
     * {@code S3_ENDPOINT} points to. The test itself looks into buckets directly, past the gate.
     */
    static final class S3 implements AutoCloseable {

        static final DockerImageName IMAGE = DockerImageName.parse("versity/versitygw:v1.8.0");

        private final String accessKey = "pdtest" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        // Characters that percent-encoding changes, so a log that holds the encoded form is found too.
        private final String secretKey = "pd/" + UUID.randomUUID() + "+k=";
        private final GenericContainer<?> container;
        private final S3Client client;
        private final Gate gate;

        private S3() {
            container = new GenericContainer<>(IMAGE)
                    .withEnv("ROOT_ACCESS_KEY", accessKey)
                    .withEnv("ROOT_SECRET_KEY", secretKey)
                    .withCommand("posix", "/tmp")
                    .withExposedPorts(7070);
            container.start();
            int port = container.getMappedPort(7070);
            client = client("http://127.0.0.1:" + port, accessKey, secretKey);
            gate = new Gate("127.0.0.1", port);
        }

        static S3 start() {
            return new S3();
        }

        static S3Client client(String endpoint, String accessKey, String secretKey) {
            return S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .httpClient(UrlConnectionHttpClient.create())
                    .build();
        }

        String accessKey() {
            return accessKey;
        }

        String secretKey() {
            return secretKey;
        }

        /** {@code S3_ENDPOINT} for the app: the gate. */
        String endpoint() {
            return "http://127.0.0.1:" + gate.port();
        }

        Gate gate() {
            return gate;
        }

        /** A new empty bucket. */
        String newBucket() {
            String bucket = "pd-" + UUID.randomUUID().toString().substring(0, 13);
            client.createBucket(b -> b.bucket(bucket));
            return bucket;
        }

        /** Every key in the bucket. */
        List<String> keys(String bucket) {
            return client.listObjectsV2Paginator(b -> b.bucket(bucket)).contents().stream().map(S3Object::key)
                    .sorted().toList();
        }

        boolean has(String bucket, String key) {
            try {
                client.headObject(b -> b.bucket(bucket).key(key));
                return true;
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return false;
                }
                throw e;
            }
        }

        /** When an object was last written, and its ETag: the same for an object nobody wrote again. */
        String written(String bucket, String key) {
            var head = client.headObject(b -> b.bucket(bucket).key(key));
            return head.lastModified() + " " + head.eTag();
        }

        boolean bucketExists(String bucket) {
            try {
                client.headBucket(b -> b.bucket(bucket));
                return true;
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return false;
                }
                throw e;
            }
        }

        byte[] read(String bucket, String key) {
            return client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        }

        void remove(String bucket, String key) {
            client.deleteObject(b -> b.bucket(bucket).key(key));
        }

        @Override
        public void close() {
            gate.close();
            client.close();
            container.stop();
        }
    }

    /**
     * A TCP listener on loopback that passes connections on to the S3 stand-in, so a test can make
     * the storage fail and come back while the app keeps its {@code S3_ENDPOINT}: {@link Mode#DROP}
     * ends every connection at once (the storage cannot be reached), {@link Mode#STALL} takes
     * connections and never answers. Changing the mode ends the connections that are open.
     * {@link #cutAfter} lets a number of bytes of answers through and then turns to {@link Mode#DROP}.
     */
    static final class Gate implements AutoCloseable {

        enum Mode {
            PASS,
            DROP,
            STALL
        }

        private final ServerSocket server;
        private final String host;
        private final int port;
        private final AtomicInteger accepted = new AtomicInteger();
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        private final AtomicLong answered = new AtomicLong();
        private volatile long cutAfter = -1;
        private volatile Mode mode = Mode.PASS;

        Gate(String host, int port) {
            this.host = host;
            this.port = port;
            try {
                server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Thread.ofPlatform().daemon().name("s3-gate").start(this::accept);
        }

        int port() {
            return server.getLocalPort();
        }

        void mode(Mode next) {
            cutAfter = -1;
            mode = next;
            open.forEach(Gate::end);
            open.clear();
        }

        /**
         * Passes on, from now, {@code bytes} bytes of what the storage answers (headers and bodies of
         * all connections together), then ends every connection and turns to {@link Mode#DROP}: a
         * storage that fails in the middle of a download. {@link #mode} takes it back.
         */
        void cutAfter(long bytes) {
            answered.set(0);
            cutAfter = bytes;
        }

        /** Connections taken so far, in any mode. */
        int accepted() {
            return accepted.get();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    accepted.incrementAndGet();
                    switch (mode) {
                        case DROP -> end(client);
                        case STALL -> open.add(client);
                        case PASS -> forward(client);
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void forward(Socket client) {
            try {
                Socket upstream = new Socket(host, port);
                open.add(client);
                open.add(upstream);
                pump(client, upstream, false);
                pump(upstream, client, true);
            } catch (IOException e) {
                end(client);
            }
        }

        /**
         * Copies one direction of a connection on a platform thread. On a virtual thread, a 9 MB
         * upload through the gate never ended on this machine (Windows, JDK 25.0.4, 2026-10-14): the
         * thread that wrote to the storage stayed parked. Smaller bodies went through.
         *
         * <p>A direction that ends in order (the sender closed) is passed on as an orderly end, and
         * only a failure or a cut resets. When both were reset, the reset threw away an answer the
         * app had not read yet: of 50 reads of the last 20,000 bytes of an object through the gate in
         * {@link Mode#PASS}, 46 ended with "Connection reset", and none of 50 without the gate
         * (2026-10-14).
         */
        private void pump(Socket from, Socket to, boolean answers) {
            Thread.ofPlatform().daemon().name("s3-gate-pump").start(() -> {
                boolean inOrder = false;
                try {
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    byte[] buffer = new byte[16 * 1024];
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        out.write(buffer, 0, n);
                        long limit = cutAfter;
                        if (answers && limit >= 0 && answered.addAndGet(n) >= limit) {
                            mode(Mode.DROP);
                            return;
                        }
                    }
                    // The sender ended in order: tell the receiver so, after what was written.
                    to.shutdownOutput();
                    inOrder = true;
                } catch (IOException e) {
                    // One side failed, or the gate ended the connection; both end below.
                } finally {
                    if (!inOrder) {
                        end(from);
                        end(to);
                        open.remove(from);
                        open.remove(to);
                    } else if (from.isOutputShutdown()) {
                        // The other direction ended in order too.
                        close(from);
                        close(to);
                        open.remove(from);
                        open.remove(to);
                    }
                }
            });
        }

        /** Closes in order; for a connection whose two directions both ended in order. */
        private static void close(Socket socket) {
            try {
                socket.close();
            } catch (IOException e) {
                // Already closed.
            }
        }

        /** Closes with a reset, so the other side sees a failure and not an orderly end. */
        private static void end(Socket socket) {
            try {
                socket.setSoLinger(true, 0);
            } catch (IOException e) {
                // Already closed.
            }
            try {
                socket.close();
            } catch (IOException e) {
                // Already closed.
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException e) {
                // Nothing listens any more either way.
            }
            open.forEach(Gate::end);
        }
    }

    /** A port on loopback that nothing listens on. */
    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Field names of a JSON object, for "the same fields as the db storage gives". */
    static Map<String, String> kinds(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().getNodeType().name()));
        return out;
    }
}
