package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Files of a mail become attachments of the issue or the comment (MAILIN-5, ATT-2, ATT-3, ATT-10). */
class MailAttachmentTest extends MailInAppTest {

    static final String LEFT_OUT = "Attachment left out: ";

    private static byte[] bytes(int size) {
        byte[] out = new byte[size];
        Arrays.fill(out, (byte) 'a');
        return out;
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** A new customer files an issue with this mail; gives its console detail. */
    private JsonNode fileByMail(Function<Inbound, Inbound> mail) {
        Company company = company();
        Session sender = customer(company.code());
        return filed(sender, mail.apply(mailFrom(sender).text("Body " + Unique.number())));
    }

    /** Puts the sender's mail in, waits a round, and gives the console detail of the one issue it made. */
    private JsonNode filed(Session sender, Inbound mail) {
        send(mail);
        rounds.next();
        return onlyIssueOf(sender);
    }

    private static List<String> names(List<JsonNode> attachments) {
        return attachments.stream().map(a -> a.path("fileName").asString("")).toList();
    }

    private static List<String> leftOutLines(String content) {
        return content.lines().filter(l -> l.startsWith(LEFT_OUT)).toList();
    }

    @Test
    @DisplayName("S12-AC-25 named attachment and inline parts become the new issue's files, at their decoded size; a part without a name does not")
    void namedPartsBecomeFiles() {
        byte[] report = text("Report " + Unique.number() + " ".repeat(500));
        byte[] picture = bytes(777);

        JsonNode issue = fileByMail(m -> m.attach("report.txt", "text/plain", report)
                .inline("picture.png", "image/png", picture)
                .unnamed("application/octet-stream", bytes(300)));

        List<JsonNode> files = attachments(issue.path("key").asString());
        assertThat(names(files)).as("files of the issue").containsExactlyInAnyOrder("report.txt", "picture.png");
        for (JsonNode f : files) {
            long size = f.path("fileName").asString("").equals("report.txt") ? report.length : picture.length;
            assertThat(f.path("size").asLong(-1)).as("size of " + f.path("fileName")).isEqualTo(size);
        }
    }

    static Stream<Arguments> fileNames() {
        return Stream.of(
                Arguments.of("RFC 2231, Korean", "보고서 최종.txt", "보고서 최종.txt"),
                Arguments.of("a Windows path", "..\\..\\a.txt", "a.txt"),
                Arguments.of("a Unix path", "../etc/b.txt", "b.txt"),
                Arguments.of("bidi controls", "invoice‮txt.exe⁦", "invoicetxt.exe"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileNames")
    @DisplayName("S12-AC-25 a file name is decoded (RFC 2231), loses any path, and loses bidi control characters")
    void fileNameIsCleaned(String what, String sent, String kept) {
        JsonNode issue = fileByMail(m -> m.attach(sent, "text/plain", text("File " + Unique.number())));

        assertThat(names(attachments(issue.path("key").asString()))).as("file name, " + what).containsExactly(kept);
    }

    @Test
    @DisplayName("S12-AC-25 a file of a reply becomes a file of the comment")
    void replyFileGoesToComment() {
        Company company = company();
        Session sender = customer(company.code());
        Issue issue = fixtures.fileIssue(sender, company.code());
        String name = "reply-" + Unique.number() + ".txt";

        send(mailFrom(sender).subject("Re: [" + issue.key() + "] " + Unique.number()).text("See file")
                .attach(name, "text/plain", text("File " + Unique.number())));
        rounds.next();

        List<JsonNode> mine = comments(issue.key()).stream()
                .filter(c -> c.path("author").path("accountId").asLong(-1) == sender.accountId()).toList();
        assertThat(mine).as("comments of the sender").hasSize(1);
        assertThat(attachments(issue.key())).as("files of " + issue.key()).singleElement().satisfies(f -> {
            assertThat(f.path("fileName").asString("")).isEqualTo(name);
            assertThat(f.path("commentId").asLong(-1)).isEqualTo(mine.get(0).path("id").asLong());
        });
    }

    @Test
    @DisplayName("S12-AC-26 a file over 10 MB is left out, the other one is kept, and the content ends with the line naming it")
    void fileOverTenMegabytes() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Body " + Unique.number())
                .attach("big.bin", "application/octet-stream", bytes(10 * 1024 * 1024 + 1))
                .attach("small.txt", "text/plain", text("Small " + Unique.number()));
        JsonNode issue;
        try {
            issue = filed(sender, mail);
        } finally {
            mailIn().remove(mail.messageId());
        }

        assertThat(names(attachments(issue.path("key").asString()))).as("files").containsExactly("small.txt");
        String content = issue.path("content").asString("");
        assertThat(leftOutLines(content)).as("left-out lines of " + content)
                .containsExactly(LEFT_OUT + "big.bin (over 10 MB)");
        assertThat(content).as("content").endsWith(LEFT_OUT + "big.bin (over 10 MB)");
    }

    @Test
    @DisplayName("S12-AC-26 of 21 files, 20 are kept and one line names the one left out")
    void twentyOneFiles() {
        JsonNode issue = fileByMail(m -> {
            for (int i = 1; i <= 21; i++) {
                m.attach(String.format(Locale.ROOT, "f%02d.txt", i), "text/plain", text("File " + i));
            }
            return m;
        });

        assertThat(attachments(issue.path("key").asString())).as("files").hasSize(20);
        assertThat(leftOutLines(issue.path("content").asString(""))).as("left-out lines").singleElement().asString()
                .matches("Attachment left out: f\\d\\d\\.txt \\(too many files\\)");
    }

    @Test
    @DisplayName("S12-AC-26 the line of a left-out file whose name holds a line break stays one line")
    void leftOutNameWithLineBreak() {
        JsonNode issue = fileByMail(m -> m.attach("bad\nname.txt", "text/plain", new byte[0])
                .attach("good.txt", "text/plain", text("Good " + Unique.number())));

        String content = issue.path("content").asString("");
        assertThat(names(attachments(issue.path("key").asString()))).as("files").containsExactly("good.txt");
        assertThat(leftOutLines(content)).as("left-out lines of " + content).singleElement().asString()
                .contains("bad").contains("name.txt").endsWith("(empty file)");
        assertThat(content.lines().filter(l -> l.startsWith("name.txt"))).as("a line that starts in the name").isEmpty();
    }
}
