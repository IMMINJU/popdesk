package io.popdesk.attachment;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.JsonNode;

/**
 * Steps shared by the tests of slice 5 (images in content): image bytes of each format, uploading
 * and reading a temporary image, and the requests that save an issue or a comment with
 * {@code imageIds}. Only HTTP. What {@link AttachmentSteps}, {@link IssueFlow}, and {@link TalkFlow}
 * already do is used from there.
 *
 * <p>A method named {@code ...Request} gives a request without sending it; the others that send
 * expect their one status and fail with an {@link AssertionError} otherwise, so before the slice is
 * built a test fails at the step, on an assertion.
 */
public final class ImageSteps {

    /** A temporary image is gone 24 hours after its upload (ATT-7). */
    public static final Duration LIFETIME = Duration.ofHours(24);

    /** The name of the job that deletes expired temporary images ({@code design.md} 0절 5). */
    public static final String CLEANUP_JOB = "temporary-image-cleanup";

    /** The most temporary images one account has at a time (ATT-7). */
    public static final int MAX_IMAGES = 20;

    /** An ID in the 36-character form of a UUID. */
    public static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The four formats (GIF in both versions): the {@code Content-Type} the server gives each, the
     * bytes that tell the format, and the start of a one-pixel file of it. The server never decodes
     * an image ({@code acceptance.md} 5절 3), so the tests do not depend on these being complete files.
     */
    public enum Format {
        PNG("image/png", "png", "iVBORw0KGgo=",
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="),
        JPEG("image/jpeg", "jpg", "/9j/",
                "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"
                        + "CAgICAgICAgICAgICAgICAj/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACv/aAAgBAQAAPwDSzyD/2Q=="),
        GIF87A("image/gif", "gif", "R0lGODdh", "R0lGODdhAQABAIAAAP///wAAACwAAAAAAQABAAACAkQBADs="),
        GIF89A("image/gif", "gif", "R0lGODlh", "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"),
        WEBP("image/webp", "webp", "UklGRhoAAABXRUJQ", "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

        private final String contentType;
        private final String extension;
        private final byte[] signature;
        private final byte[] head;

        Format(String contentType, String extension, String signature, String head) {
            this.contentType = contentType;
            this.extension = extension;
            this.signature = Base64.getDecoder().decode(signature);
            this.head = Base64.getDecoder().decode(head);
        }

        /** The {@code Content-Type} an image of this format is served with. */
        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        /** Only the first bytes that tell the format (for WebP {@code RIFF}, a length, {@code WEBP}). */
        public byte[] signature() {
            return signature.clone();
        }
    }

    private ImageSteps() {}

    // Image bytes

    /** A small image of the format: a one-pixel file followed by 64 bytes that depend on the seed. */
    public static byte[] image(Format format, long seed) {
        return image(format, format.head.length + 64, seed);
    }

    /** An image of exactly {@code size} bytes: the one-pixel file, then bytes that depend on the seed. */
    public static byte[] image(Format format, int size, long seed) {
        if (size < format.head.length) {
            throw new IllegalArgumentException("A " + format + " here has at least " + format.head.length + " bytes");
        }
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        System.arraycopy(format.head, 0, out, 0, format.head.length);
        return out;
    }

    /** A small PNG no other test has. */
    public static byte[] png() {
        return image(Format.PNG, Unique.number());
    }

    /** The first bytes of the format followed by bytes that are no image at all. */
    public static byte[] brokenAfterSignature(Format format, long seed) {
        byte[] signature = format.signature();
        byte[] out = Arrays.copyOf(signature, signature.length + 200);
        byte[] tail = new byte[200];
        new Random(seed).nextBytes(tail);
        System.arraycopy(tail, 0, out, signature.length, tail.length);
        return out;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Temporary images

    /** {@code /api/{app}/images/{id}} on the caller's app. */
    public static String imagePath(Session session, String id) {
        return session.api("/images/" + id);
    }

    /** {@code POST /api/{app}/images} with this file part as it is, not sent yet. */
    public static ApiRequest uploadRequest(Session session, String fileName, String contentType, byte[] content) {
        return session.client().post(session.api("/images")).file("file", fileName, contentType, content);
    }

    /** Uploads a temporary image, whatever the answer. The part says {@code application/octet-stream}. */
    public static ApiResponse upload(Session session, String fileName, byte[] content) {
        return uploadRequest(session, fileName, "application/octet-stream", content).send();
    }

    /** Uploads a temporary image and gives its ID. */
    public static String uploaded(Session session, String fileName, byte[] content) {
        return upload(session, fileName, content).expectStatus(201).jsonText("id");
    }

    /** Uploads a temporary image under a name of its own and gives its ID. */
    public static String uploaded(Session session, byte[] content) {
        return uploaded(session, "image-" + Unique.number() + ".png", content);
    }

    /** Uploads a small PNG no other test has and gives its ID. */
    public static String uploaded(Session session) {
        return uploaded(session, png());
    }

    /** {@code GET /api/{app}/images/{id}}, whatever the answer. */
    public static ApiResponse read(Session session, String id) {
        return session.client().get(imagePath(session, id)).send();
    }

    /** An ID in UUID form that no image has. */
    public static String unknownId() {
        return UUID.randomUUID().toString();
    }

    /**
     * For {@code GET}, a range request, and {@code HEAD}: the caller's request for the temporary image
     * {@code id} answers 404 with the same headers (but {@code Date}) and the same body as its request
     * for an ID nobody has.
     */
    public static void assertReadsLikeUnknown(Session caller, String id, String what) {
        String unknown = imagePath(caller, unknownId());
        String target = imagePath(caller, id);
        SoftAssertions softly = new SoftAssertions();
        Map<String, Function<String, ApiRequest>> requests = new LinkedHashMap<>();
        requests.put("GET", path -> caller.client().get(path));
        requests.put("range", path -> caller.client().get(path).header("Range", "bytes=0-9"));
        requests.put("HEAD", path -> caller.client().head(path));
        requests.forEach((name, request) -> {
            ApiResponse expected = request.apply(unknown).send();
            ApiResponse actual = request.apply(target).send();
            softly.assertThat(expected.status()).as("%s of an unknown image ID", name).isEqualTo(404);
            softly.assertThat(actual.status()).as("%s status for %s", name, what).isEqualTo(expected.status());
            softly.assertThat(AttachmentSteps.headersWithoutDate(actual)).as("%s headers for %s", name, what)
                    .isEqualTo(AttachmentSteps.headersWithoutDate(expected));
            softly.assertThat(actual.body()).as("%s body for %s", name, what).isEqualTo(expected.body());
        });
        softly.assertAll();
    }

    /**
     * For download, range request, {@code HEAD}, and delete (delete last) under an issue: the caller's
     * request for the attachment {@code id} under {@code key} answers 404 with the same headers (but
     * {@code Date}) and the same body as its request for an ID nobody has under {@code ownKey}, an
     * issue the caller sees (S0-AC-102).
     */
    public static void assertAttachmentLikeUnknown(Session caller, String ownKey, String key, String id,
            String what) {
        String unknown = AttachmentSteps.path(caller, ownKey, unknownId());
        String target = AttachmentSteps.path(caller, key, id);
        SoftAssertions softly = new SoftAssertions();
        Map<String, Function<String, ApiRequest>> requests = new LinkedHashMap<>();
        requests.put("download", path -> caller.client().get(path));
        requests.put("range", path -> caller.client().get(path).header("Range", "bytes=0-9"));
        requests.put("HEAD", path -> caller.client().head(path));
        requests.put("delete", path -> caller.client().delete(path));
        requests.forEach((name, request) -> {
            ApiResponse expected = request.apply(unknown).send();
            ApiResponse actual = request.apply(target).send();
            softly.assertThat(expected.status()).as("%s of an unknown attachment ID", name).isEqualTo(404);
            softly.assertThat(actual.status()).as("%s status for %s", name, what).isEqualTo(expected.status());
            softly.assertThat(AttachmentSteps.headersWithoutDate(actual)).as("%s headers for %s", name, what)
                    .isEqualTo(AttachmentSteps.headersWithoutDate(expected));
            softly.assertThat(actual.body()).as("%s body for %s", name, what).isEqualTo(expected.body());
        });
        softly.assertAll();
    }

    /** IDs of the issue's attachments the caller sees that are images of the content ({@code inline}). */
    public static List<String> inlineIds(Session session, String key) {
        return AttachmentSteps.list(session, key).stream().filter(n -> n.path("inline").asBoolean(false))
                .map(n -> AttachmentSteps.text(n, "id")).toList();
    }

    // The cleanup of temporary images

    /**
     * Waits until the system status lists the cleanup of temporary images with a run at or after
     * {@code since}, and gives that line ({@code name}, {@code lastRunAt}, {@code deleted}).
     * {@code admin} must be signed in at the app's time now.
     */
    public static JsonNode awaitCleanup(Session admin, Instant since) {
        return Wait.until(CLEANUP_JOB + " in the system status with a run at or after " + since,
                Duration.ofSeconds(90), () -> cleanupRun(admin, since));
    }

    /** The line of the cleanup in the system status when its last run is at or after {@code since}, else null. */
    public static JsonNode cleanupRun(Session admin, Instant since) {
        JsonNode jobs = admin.client().get("/api/console/system/status").expect(200).json().path("cleanupJobs");
        for (JsonNode job : jobs) {
            if (CLEANUP_JOB.equals(job.path("name").asString(null)) && job.path("lastRunAt").isString()
                    && !instant(job.path("lastRunAt").stringValue()).isBefore(since)) {
                return job;
            }
        }
        return null;
    }

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time: " + text, e);
        }
    }

    // Writes that carry imageIds

    /**
     * One issue and the people around it: the customer who filed it, a colleague of the same company,
     * an agent assigned to the company, the first admin, a public comment of the customer, a public
     * and an internal comment of the agent, and {@code marker}, the text the next {@link Write} saves.
     */
    public record Stage(Session admin, Company company, Session customer, Session colleague, Session agent,
            Issue issue, Comment customerComment, Comment agentComment, Comment internalComment, String marker) {

        public String code() {
            return company.code();
        }

        public String key() {
            return issue.key();
        }

        /** The same stage with another text for the next write. */
        public Stage withMarker(String next) {
            return new Stage(admin, company, customer, colleague, agent, issue, customerComment, agentComment,
                    internalComment, next);
        }
    }

    public static Stage stage(Fixtures fixtures) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        Comment customerComment = fixtures.comment(customer, issue.key(), Unique.text("Question"));
        Comment agentComment = fixtures.comment(agent, issue.key(), Unique.text("Answer"));
        Comment internalComment = fixtures.internalComment(agent, issue.key(), Unique.text("Note"));
        return new Stage(admin, company, customer, colleague, agent, issue, customerComment, agentComment,
                internalComment, Unique.text("Image write"));
    }

    /** Where a write put its images: the issue, and the comment when it wrote one. */
    public record Place(String issueKey, Long commentId) {}

    /**
     * The requests that save an issue or a comment and take {@code imageIds} (S5-AC-20): the eight
     * operations of the two apps, filing on the portal also as multipart, and an internal comment.
     * Each writes the stage's {@code marker}: as the title when it files, as the content otherwise.
     */
    public enum Write {
        PORTAL_FILING(Kind.FILING, false),
        PORTAL_FILING_MULTIPART(Kind.FILING, false),
        ON_BEHALF_FILING(Kind.FILING, true),
        PORTAL_ISSUE_EDIT(Kind.ISSUE_EDIT, false),
        CONSOLE_ISSUE_EDIT(Kind.ISSUE_EDIT, true),
        PORTAL_COMMENT(Kind.COMMENT, false),
        CONSOLE_COMMENT(Kind.COMMENT, true),
        CONSOLE_INTERNAL_COMMENT(Kind.COMMENT, true),
        PORTAL_COMMENT_EDIT(Kind.COMMENT_EDIT, false),
        CONSOLE_COMMENT_EDIT(Kind.COMMENT_EDIT, true),
        CONSOLE_INTERNAL_COMMENT_EDIT(Kind.COMMENT_EDIT, true);

        /** What a write does. */
        public enum Kind {
            FILING,
            ISSUE_EDIT,
            COMMENT,
            COMMENT_EDIT
        }

        private final Kind kind;
        private final boolean console;

        Write(Kind kind, boolean console) {
            this.kind = kind;
            this.console = console;
        }

        public Kind kind() {
            return kind;
        }

        /** Whether the images end up on an internal comment. */
        public boolean internal() {
            return this == CONSOLE_INTERNAL_COMMENT || this == CONSOLE_INTERNAL_COMMENT_EDIT;
        }

        /** Who sends it: the customer on the portal, the agent on the console. */
        public Session writer(Stage stage) {
            return console ? stage.agent() : stage.customer();
        }

        /** 201 for a new issue or comment, 200 for an edit. */
        public int success() {
            return kind == Kind.FILING || kind == Kind.COMMENT ? 201 : 200;
        }

        /** The comment an edit changes, or null. */
        private Comment edited(Stage stage) {
            if (kind != Kind.COMMENT_EDIT) {
                return null;
            }
            return internal() ? stage.internalComment() : console ? stage.agentComment() : stage.customerComment();
        }

        /**
         * The request with these {@code imageIds} (UUID texts, or anything else for a test about bad
         * ones), not sent yet. An issue edit reads the issue's version first.
         */
        public ApiRequest request(Stage stage, Collection<?> imageIds) {
            Session writer = writer(stage);
            List<?> ids = List.copyOf(imageIds);
            return switch (this) {
                case PORTAL_FILING -> writer.client().post("/api/portal/issues").json(TalkFlow.body(
                        "companyCode", stage.code(), "title", stage.marker(), "content", "Filed with images.",
                        "imageIds", ids));
                case PORTAL_FILING_MULTIPART -> writer.client().post("/api/portal/issues")
                        .jsonPart("issue", TalkFlow.body("companyCode", stage.code(), "title", stage.marker(),
                                "content", "Filed with a file and images.", "imageIds", ids))
                        .file("files", "note-" + Unique.number() + ".txt", "text/plain",
                                "A file sent with the issue.".getBytes(StandardCharsets.UTF_8));
                case ON_BEHALF_FILING -> writer.client().post("/api/console/issues").json(TalkFlow.body(
                        "companyCode", stage.code(), "reporterId", stage.customer().accountId(), "channel", "PHONE",
                        "title", stage.marker(), "content", "Taken down with images.", "imageIds", ids));
                case PORTAL_ISSUE_EDIT, CONSOLE_ISSUE_EDIT -> IssueFlow.editRequest(writer, stage.key(),
                        TalkFlow.body("content", stage.marker(), "version", version(writer, stage.key()),
                                "imageIds", ids));
                case PORTAL_COMMENT, CONSOLE_COMMENT -> TalkFlow.addRequest(writer, stage.key(),
                        TalkFlow.body("content", stage.marker(), "imageIds", ids));
                case CONSOLE_INTERNAL_COMMENT -> TalkFlow.addRequest(writer, stage.key(),
                        TalkFlow.body("content", stage.marker(), "internal", true, "imageIds", ids));
                case PORTAL_COMMENT_EDIT, CONSOLE_COMMENT_EDIT, CONSOLE_INTERNAL_COMMENT_EDIT -> TalkFlow.editRequest(
                        writer, stage.key(), edited(stage).id(), TalkFlow.body("content", stage.marker(),
                                "imageIds", ids));
            };
        }

        /** Where the write whose answer is {@code saved} put its images. */
        public Place place(Stage stage, ApiResponse saved) {
            return switch (kind) {
                case FILING -> new Place(saved.jsonText("key"), null);
                case ISSUE_EDIT -> new Place(stage.key(), null);
                case COMMENT -> new Place(stage.key(), saved.jsonLong("id"));
                case COMMENT_EDIT -> new Place(stage.key(), edited(stage).id());
            };
        }

        /**
         * Whether the text of this write is stored now: an issue of the company with the marker as its
         * title, the issue's content, a comment with the marker as its content, or the edited comment's.
         */
        public boolean saved(Stage stage) {
            Session writer = writer(stage);
            return switch (kind) {
                case FILING -> TalkFlow.items(IssueFlow.list(stage.customer())).stream()
                        .anyMatch(issue -> stage.marker().equals(AttachmentSteps.text(issue, "title")));
                case ISSUE_EDIT -> stage.marker().equals(AttachmentSteps.text(detail(writer, stage.key()), "content"));
                case COMMENT -> TalkFlow.comments(writer, stage.key()).stream()
                        .anyMatch(comment -> stage.marker().equals(AttachmentSteps.text(comment, "content")));
                case COMMENT_EDIT -> stage.marker().equals(AttachmentSteps.text(
                        TalkFlow.comment(writer, stage.key(), edited(stage).id()), "content"));
            };
        }
    }

    /** The issue as the caller sees it. */
    public static JsonNode detail(Session session, String key) {
        return session.client().get(session.api("/issues/" + key)).expect(200).json();
    }

    public static long version(Session session, String key) {
        JsonNode version = detail(session, key).path("version");
        if (!version.isIntegralNumber()) {
            throw new AssertionError("No version in issue " + key);
        }
        return version.asLong();
    }

    /** Adds a comment with these images on the caller's app and gives it. */
    public static Comment commentWith(Session session, String key, String content, Collection<?> imageIds) {
        return TalkFlow.add(session, key, TalkFlow.body("content", content, "imageIds", List.copyOf(imageIds)));
    }

    /** A new temporary image of the caller attached to a new public comment of theirs on the issue; its ID. */
    public static String attachedByComment(Session session, String key, byte[] content) {
        String id = uploaded(session, content);
        commentWith(session, key, Unique.text("With an image"), List.of(id));
        return id;
    }

    // History

    /** The first 100 entries of the issue's history as staff see them, oldest first. */
    public static List<JsonNode> history(Session staff, String key) {
        return TalkFlow.items(staff.client().get("/api/console/issues/" + key + "/history").query("size", 100)
                .expect(200));
    }

    /** The history entries about attachments ({@code field} is {@code ATTACHMENT}), oldest first. */
    public static List<JsonNode> attachmentHistory(Session staff, String key) {
        return history(staff, key).stream().filter(e -> "ATTACHMENT".equals(AttachmentSteps.text(e, "field")))
                .toList();
    }
}
