package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.slice;
import static io.popdesk.attachment.ImageSteps.assertReadsLikeUnknown;
import static io.popdesk.attachment.ImageSteps.image;
import static io.popdesk.attachment.ImageSteps.imagePath;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.ImageSteps.Format;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a temporary image ({@code GET} and {@code HEAD /api/{app}/images/{id}}): the account that
 * uploaded it reads it as a download, and for everyone else, and once it is attached or 24 hours
 * old, every request answers exactly as for an ID nobody has (status, headers but {@code Date}, body).
 */
class ImageReadTest extends BareAppTest {

    /** Who asks for a customer's temporary image. */
    enum Reader {
        CUSTOMER_OF_ANOTHER_COMPANY,
        COLLEAGUE,
        AGENT_OF_THE_COMPANY,
        ADMIN
    }

    @ParameterizedTest(name = "on the {0} app")
    @ValueSource(strings = {"portal", "console"})
    @DisplayName("S5-AC-10 the account that uploaded a temporary image reads the same bytes as an attachment with "
            + "nosniff and no-store, by GET, HEAD, and a range request")
    void uploaderReads(String app) {
        Stage st = ImageSteps.stage(fixtures);
        Session uploader = app.equals("portal") ? st.customer() : st.agent();
        byte[] content = image(Format.PNG, 5_000, Unique.number());
        String id = uploaded(uploader, content);
        String path = imagePath(uploader, id);

        ApiResponse whole = read(uploader, id);
        ApiResponse head = uploader.client().head(path).send();
        ApiResponse part = uploader.client().get(path).header("Range", "bytes=1000-1999").send();

        assertThat(whole.status()).as(whole.request()).isEqualTo(200);
        assertThat(whole.body()).isEqualTo(content);
        assertThat(head.status()).as(head.request()).isEqualTo(200);
        assertThat(head.body()).as("body of HEAD").isEmpty();
        assertThat(part.status()).as(part.request()).isEqualTo(206);
        assertThat(part.header("Content-Range")).isEqualTo("bytes 1000-1999/5000");
        assertThat(part.body()).isEqualTo(slice(content, 1000, 1999));
        for (ApiResponse answer : List.of(whole, head, part)) {
            assertThat(answer.header("Content-Disposition")).as("Content-Disposition of %s", answer.request())
                    .startsWith("attachment");
            assertThat(answer.header("X-Content-Type-Options")).as("nosniff of %s", answer.request())
                    .isEqualTo("nosniff");
            assertThat(answer.header("Content-Type")).as("Content-Type of %s", answer.request())
                    .isEqualTo("image/png");
            assertThat(answer.headers("Cache-Control")).as("Cache-Control of %s", answer.request())
                    .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("no-store"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Reader.class)
    @DisplayName("S5-AC-11 anyone but the uploader, an admin too, gets the unknown-ID 404 for a temporary image by "
            + "GET, HEAD, and a range request, and the uploader still reads it")
    void othersGetTheUnknownIdAnswer(Reader reader) {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = ImageSteps.png();
        String id = uploaded(st.customer(), content);
        Session other = switch (reader) {
            case CUSTOMER_OF_ANOTHER_COMPANY -> {
                Company elsewhere = fixtures.createCompany(st.admin());
                yield fixtures.customer(st.admin(), elsewhere.code());
            }
            case COLLEAGUE -> st.colleague();
            case AGENT_OF_THE_COMPANY -> st.agent();
            case ADMIN -> fixtures.admin(st.admin());
        };
        // The reader reads an image of their own on the same path, so the 404 is about whose image it is.
        assertThat(read(other, uploaded(other)).status()).as("the reader's own image").isEqualTo(200);

        assertReadsLikeUnknown(other, id, "the customer's image asked for by " + reader);

        assertThat(read(st.customer(), id).body()).as("the image for its uploader afterwards").isEqualTo(content);
    }

    @Test
    @DisplayName("S5-AC-11 an image uploaded on the console is the unknown-ID 404 on the portal's path, and the "
            + "other way round")
    void pathOfTheOtherApp() {
        Stage st = ImageSteps.stage(fixtures);
        String ofAgent = uploaded(st.agent());
        String ofCustomer = uploaded(st.customer());

        assertReadsLikeUnknown(st.customer(), ofAgent, "an agent's image on the portal's path");
        assertReadsLikeUnknown(st.agent(), ofCustomer, "a customer's image on the console's path");
    }

    @Test
    @DisplayName("S5-AC-12 once attached, an image is the unknown-ID 404 on the temporary path for its uploader, "
            + "and is read under the issue")
    void attachedImageLeavesTheTemporaryPath() {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = ImageSteps.png();
        String id = uploaded(st.customer(), content);
        assertThat(read(st.customer(), id).status()).as("before it is attached").isEqualTo(200);

        ImageSteps.commentWith(st.customer(), st.key(), "Attaching", List.of(id));

        assertReadsLikeUnknown(st.customer(), id, "an attached image on the temporary path");
        assertThat(AttachmentSteps.download(st.customer(), st.key(), id).body()).as("the image under its issue")
                .isEqualTo(content);
    }

    @Test
    @DisplayName("S5-AC-12, S5-AC-30 a second before 24 hours the uploader still reads a temporary image; a second "
            + "after, it is the unknown-ID 404, whether the cleanup ran or not")
    void expiredImageIsNotRead() {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = ImageSteps.png();
        String id = uploaded(st.customer(), content);

        clock.advance(ImageSteps.LIFETIME.minus(Duration.ofSeconds(1)));
        Session later = fixtures.signInAgain(st.customer());
        ApiResponse justBefore = read(later, id);
        clock.advance(Duration.ofSeconds(2));

        assertThat(justBefore.status()).as("a second before 24 hours: %s", justBefore.snippet()).isEqualTo(200);
        assertThat(justBefore.body()).isEqualTo(content);
        // A new image is read, so the 404 is about the age of the old one.
        assertThat(read(later, uploaded(later)).status()).as("a new image of the same account").isEqualTo(200);
        assertReadsLikeUnknown(later, id, "an image older than 24 hours");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-00000000000", "zzzzzzzz-zzzz-zzzz-zzzz-"
            + "zzzzzzzzzzzz"})
    @DisplayName("S5-AC-12 an ID that is not a UUID in its 36-character form is the unknown-ID 404 on the "
            + "temporary path")
    void idThatIsNoUuid(String id) {
        Stage st = ImageSteps.stage(fixtures);
        assertThat(read(st.customer(), uploaded(st.customer())).status()).as("an image of the caller").isEqualTo(200);

        assertReadsLikeUnknown(st.customer(), id, "the ID " + id);
    }
}
