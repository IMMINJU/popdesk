package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a test email says the SMTP server answered, by the exceptions of Jakarta Mail alone. Written
 * from the acceptance criteria, the contract ({@code TestMailResult.serverReply}), and the signature,
 * not from the body of the method.
 */
class SmtpServerReplyTest {

    private static final String USER = "mailer-account";
    private static final String PASSWORD = "s3cr3t-Pa55word";
    private static final String TO = "zed@example.test";

    private static InternetAddress address(String address) {
        try {
            return new InternetAddress(address);
        } catch (AddressException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The server refused the message with this reply. */
    private static MessagingException refusedMessage(String reply) {
        return new SMTPSendFailedException("DATA", 554, reply, null, new Address[0], new Address[0], new Address[0]);
    }

    private static String replyOf(MessagingException e) {
        return SmtpMail.serverReply(e, USER, PASSWORD, TO);
    }

    // S4B-AC-23

    static Stream<Arguments> refusals() {
        InternetAddress other = address("someone@elsewhere.test");
        return Stream.of(
                Arguments.of(Named.of("a refused sign-in",
                        new AuthenticationFailedException("535 5.7.8 Authentication credentials invalid")),
                        "535 5.7.8 Authentication credentials invalid"),
                Arguments.of(Named.of("a refused message", refusedMessage("554 5.6.0 Message content rejected")),
                        "554 5.6.0 Message content rejected"),
                Arguments.of(Named.of("a refused address",
                        new SMTPAddressFailedException(other, "RCPT TO", 550, "550 5.1.1 Mailbox unknown")),
                        "550 5.1.1 Mailbox unknown"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    @DisplayName("S4B-AC-23 a refusal of the server comes back as the text the server answered")
    void refusalGivesTheReply(MessagingException refusal, String reply) {
        assertThat(replyOf(refusal)).isEqualTo(reply);
    }

    static Stream<Arguments> wrappedRefusals() {
        InternetAddress other = address("someone@elsewhere.test");
        SMTPAddressFailedException refusedAddress =
                new SMTPAddressFailedException(other, "RCPT TO", 550, "550 5.1.1 Mailbox unknown");
        SMTPSendFailedException refusedSender = new SMTPSendFailedException("MAIL FROM", 553,
                "553 5.1.8 Sender not allowed", null, new Address[0], new Address[0], new Address[0]);
        refusedSender.setNextException(
                new SMTPSenderFailedException(other, "MAIL FROM", 553, "553 5.1.8 Sender not allowed"));
        return Stream.of(
                Arguments.of(Named.of("a refused sender as Angus Mail reports it", refusedSender),
                        "553 5.1.8 Sender not allowed"),
                Arguments.of(Named.of("refused addresses as Jakarta Mail reports them",
                        new SendFailedException("Invalid Addresses", refusedAddress)), "550 5.1.1 Mailbox unknown"),
                Arguments.of(Named.of("a refused message inside a plain failure",
                        new MessagingException("Sending failed", refusedMessage("554 5.6.0 Message content rejected"))),
                        "554 5.6.0 Message content rejected"),
                Arguments.of(Named.of("a refused message two levels down",
                        new MessagingException("Sending failed", new MessagingException("Could not send",
                                refusedMessage("554 5.6.0 Message content rejected")))),
                        "554 5.6.0 Message content rejected"));
    }

    @ParameterizedTest
    @MethodSource("wrappedRefusals")
    @DisplayName("S4B-AC-23 a refusal inside another exception still gives the text the server answered")
    void wrappedRefusalGivesTheReply(MessagingException failure, String reply) {
        assertThat(replyOf(failure)).contains(reply);
    }

    static Stream<Named<MessagingException>> failuresWithoutAReply() {
        return Stream.of(
                Named.of("a failure with no text", new MessagingException()),
                Named.of("a failure with a text of the library", new MessagingException("Exception reading response")),
                Named.of("a refused connection", new MessagingException(
                        "Couldn't connect to host, port: localhost, 2525; timeout 10000",
                        new ConnectException("Connection refused"))),
                Named.of("a server that does not answer in time",
                        new MessagingException("Exception reading response",
                                new SocketTimeoutException("Read timed out"))),
                Named.of("a failed TLS handshake", new MessagingException("Could not convert socket to TLS",
                        new SSLHandshakeException("No appropriate protocol"))));
    }

    @ParameterizedTest
    @MethodSource("failuresWithoutAReply")
    @DisplayName("S4B-AC-23 a failure in which the server answered nothing has no reply: null")
    void otherFailureHasNoReply(MessagingException failure) {
        assertThat(replyOf(failure)).isNull();
    }

    // S4B-AC-24: the SMTP user name and password

    static Stream<Arguments> secrets() {
        return Stream.of(
                Arguments.of("the user name", USER),
                Arguments.of("the password", PASSWORD),
                Arguments.of("the user name in Base64", base64(USER)),
                Arguments.of("the password in Base64", base64(PASSWORD)),
                Arguments.of("the user name and password of AUTH PLAIN", base64("\0" + USER + "\0" + PASSWORD)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secrets")
    @DisplayName("S4B-AC-24 a reply that repeats the SMTP user name or password, as it is or in Base64, comes back "
            + "without it and with the rest of its text")
    void secretIsTakenOut(String what, String secret) {
        String reply = replyOf(new AuthenticationFailedException("535 5.7.8 refused " + secret + " as given"));

        assertThat(reply).as(what).doesNotContain(secret).startsWith("535 5.7.8 refused ").endsWith(" as given");
    }

    @Test
    @DisplayName("S4B-AC-24 the user name and the password are both taken out of one reply, each time they come, and "
            + "*** stands in their place")
    void everySecretOfAReplyIsTakenOut() {
        String reply = replyOf(refusedMessage("554 5.7.1 " + USER + ":" + PASSWORD + " no, " + PASSWORD + " and "
                + USER + " again"));

        assertThat(reply).doesNotContain(USER).doesNotContain(PASSWORD).contains("***").startsWith("554 5.7.1 ")
                .endsWith(" again");
    }

    static Stream<Arguments> placesOfASecret() {
        return Stream.of(
                Arguments.of("at the start", PASSWORD + " was refused", " was refused"),
                Arguments.of("at the end", "535 refused: " + PASSWORD, "535 refused: "),
                Arguments.of("as the whole reply", PASSWORD, ""),
                Arguments.of("twice in a row", "535 " + PASSWORD + PASSWORD + " refused", " refused"),
                Arguments.of("the user name at the start", USER + " was refused", " was refused"),
                Arguments.of("the user name at the end", "535 refused: " + USER, "535 refused: "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("placesOfASecret")
    @DisplayName("S4B-AC-24 a secret is taken out wherever it stands in the reply")
    void secretIsTakenOutAtAnyPlace(String where, String answered, String rest) {
        String reply = replyOf(new AuthenticationFailedException(answered));

        assertThat(reply).as(where).doesNotContain(PASSWORD).doesNotContain(USER).contains(rest);
    }

    @Test
    @DisplayName("S4B-AC-24 a password that begins with the user name leaves no part of itself in the reply")
    void passwordThatHoldsTheUserName() {
        MessagingException refusal = new AuthenticationFailedException("535 5.7.8 refused mailer-Pa55word as given");

        String reply = SmtpMail.serverReply(refusal, "mailer", "mailer-Pa55word", TO);

        assertThat(reply).doesNotContain("mailer").doesNotContain("Pa55word").startsWith("535 5.7.8 refused ")
                .endsWith(" as given");
    }

    @Test
    @DisplayName("S4B-AC-24 the SMTP user name is taken out in any case of its letters")
    void userNameInAnotherCase() {
        String reply = replyOf(new AuthenticationFailedException("535 5.7.8 refused MAILER-Account as given"));

        assertThat(reply).doesNotContainIgnoringCase(USER).startsWith("535 5.7.8 refused ").endsWith(" as given");
    }

    @Test
    @DisplayName("S4B-AC-24 a secret in a refusal inside another exception is taken out as well")
    void secretOfAWrappedRefusalIsTakenOut() {
        MessagingException failure = new MessagingException("Sending failed",
                refusedMessage("554 5.7.1 refused " + PASSWORD + " for " + USER + " here"));

        assertThat(replyOf(failure)).doesNotContain(PASSWORD).doesNotContain(USER).contains("554 5.7.1 refused ")
                .contains(" here");
    }

    @Test
    @DisplayName("S4B-AC-24 with no SMTP user name the reply still comes back, without the password")
    void noUserName() {
        MessagingException refusal = refusedMessage("554 5.7.1 refused " + PASSWORD + " as given");

        String reply = SmtpMail.serverReply(refusal, null, PASSWORD, TO);

        assertThat(reply).doesNotContain(PASSWORD).startsWith("554 5.7.1 refused ").endsWith(" as given");
    }

    @Test
    @DisplayName("S4B-AC-24 with neither an SMTP user name nor a password the reply comes back as the server gave it")
    void noUserNameAndNoPassword() {
        MessagingException refusal = refusedMessage("554 5.7.1 Relaying denied");

        assertThat(SmtpMail.serverReply(refusal, null, null, TO)).isEqualTo("554 5.7.1 Relaying denied");
    }

    // S4B-AC-24: the address the email went to (the contract: masked after its first character)

    @ParameterizedTest
    @ValueSource(strings = {
        "550 5.1.1 <zed@example.test>: Recipient address rejected",
        "550 5.1.1 zed@example.test unknown",
        "550 5.1.1 unknown: zed@example.test"
    })
    @DisplayName("S4B-AC-24 a reply that repeats the address the email went to comes back without the address, "
            + "with its first character and the rest of the text")
    void addressIsMasked(String answered) {
        String reply = replyOf(new SMTPAddressFailedException(address(TO), "RCPT TO", 550, answered));

        assertThat(reply).doesNotContain(TO).doesNotContain("zed").doesNotContain("ed@example.test")
                .startsWith("550 5.1.1 ").contains("z").containsAnyOf("unknown", "Recipient address rejected");
    }

    @Test
    @DisplayName("S4B-AC-24 the address the email went to is masked in any case of its letters")
    void addressInAnotherCaseIsMasked() {
        String reply = replyOf(new SMTPAddressFailedException(address(TO), "RCPT TO", 550,
                "550 5.1.1 <ZED@Example.Test>: Recipient address rejected"));

        assertThat(reply).doesNotContainIgnoringCase(TO).doesNotContainIgnoringCase("ed@example.test")
                .startsWith("550 5.1.1 ").contains("Recipient address rejected");
    }

    @Test
    @DisplayName("S4B-AC-24 an address in the reply that is not the one the email went to stays as it is")
    void otherAddressIsNotMasked() {
        String answered = "553 5.1.8 <support@popdesk.test>: Sender address rejected";

        assertThat(replyOf(refusedMessage(answered))).isEqualTo(answered);
    }

    // S4B-AC-24: 500 characters

    @ParameterizedTest
    @ValueSource(ints = {1, 499, 500})
    @DisplayName("S4B-AC-24 a reply of up to 500 characters comes back whole")
    void shortReplyIsWhole(int length) {
        String answered = "554 5.6.0 ".substring(0, Math.min(length, 10)) + "x".repeat(Math.max(0, length - 10));

        assertThat(replyOf(refusedMessage(answered))).hasSize(length).isEqualTo(answered);
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 502, 1000, 5000})
    @DisplayName("S4B-AC-24 a reply of more than 500 characters comes back as its first 500")
    void longReplyIsCutAtFiveHundred(int length) {
        String answered = "554 5.6.0 " + "abcdefghij".repeat(length / 10 + 1).substring(0, length - 10);

        assertThat(replyOf(refusedMessage(answered))).hasSize(500).isEqualTo(answered.substring(0, 500));
    }

    // S4B-AC-24: control characters

    @ParameterizedTest
    @ValueSource(chars = {'\t', '\r', '\u0000', '\u001b', '\u0007', '\u0008', '\u000b', '\u000c', '\u001f', '\u007f'})
    @DisplayName("S4B-AC-24 a control character that is no line break is taken out of the reply, and the text on "
            + "both sides of it stays")
    void controlCharacterIsTakenOut(char control) {
        String reply = replyOf(refusedMessage("554 5.6.0 left" + control + "right" + control));

        assertThat(reply).doesNotContain(String.valueOf(control)).startsWith("554 5.6.0 left").contains("right");
        assertThat(reply.chars().filter(Character::isISOControl).count()).as("control characters in the reply")
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(chars = {'\u0080', '\u0085', '\u009f'})
    @DisplayName("S4B-AC-24 a control character of the second control block (U+0080 to U+009F) is taken out of the "
            + "reply as well")
    void upperControlCharacterIsTakenOut(char control) {
        String reply = replyOf(refusedMessage("554 5.6.0 left" + control + "right" + control));

        assertThat(reply).doesNotContain(String.valueOf(control)).startsWith("554 5.6.0 left").contains("right");
    }

    @Test
    @DisplayName("S4B-AC-24 characters next to the control characters in the code table are no control characters "
            + "and stay: a space, a tilde, a no-break space, letters of other scripts")
    void otherCharactersStay() {
        String answered = "554 5.6.0 a b~c\u00a0d \u00a1 \u00e9 \ud55c\uae00 !";

        assertThat(replyOf(refusedMessage(answered))).isEqualTo(answered);
    }

    @Test
    @DisplayName("S4B-AC-24 a reply of several lines keeps its line breaks")
    void lineBreaksStay() {
        String answered = "554-5.6.0 first line\n554-5.6.0 second line\n554 5.6.0 third line";

        assertThat(replyOf(refusedMessage(answered))).isEqualTo(answered);
    }

    @Test
    @DisplayName("S4B-AC-24 of a line break as SMTP writes it and control characters around it, the line break "
            + "stays and nothing else")
    void lineBreakAmongControlCharactersStays() {
        String reply = replyOf(refusedMessage("554-5.6.0 bell\u0007here\r\n554 5.6.0 esc\u001b[31m red\u0000"));

        assertThat(reply.chars().filter(ch -> Character.isISOControl(ch) && ch != '\n').count())
                .as("control characters other than the line break in the reply").isZero();
        assertThat(reply.chars().filter(ch -> ch == '\n').count()).as("line breaks").isEqualTo(1);
        assertThat(reply).contains("bell").contains("here").contains("esc").contains("[31m red");
        assertThat(reply.indexOf('\n')).as("the line break is between the two lines").isBetween(
                reply.indexOf("here"), reply.indexOf("554 5.6.0 esc"));
    }
}
