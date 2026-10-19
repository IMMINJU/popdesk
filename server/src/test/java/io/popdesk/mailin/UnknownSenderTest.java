package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps;
import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Mail from someone who is not an active customer, or whose {@code From} is not one address (MAILIN-1, D-16, PII-2):
 * discarded, with no issue and no mail to anyone, and only a masked address in the log.
 *
 * <p>Each case has a customer of one company, active or once active, whose address is somewhere in the mail, so a mail
 * read the wrong way would become that customer's issue.
 */
class UnknownSenderTest extends MailInAppTest {

    enum Sender {
        /** No account has the address. */
        NO_ACCOUNT,
        /** An agent's address. */
        AGENT,
        /** An admin's address. */
        ADMIN,
        /** A customer invited who has not accepted yet. */
        INVITED,
        /** A suspended customer. */
        SUSPENDED,
        /** The old address of an anonymized customer. */
        ANONYMIZED,
        /** Two {@code From} headers, the second one the customer's. */
        TWO_FROM_HEADERS,
        /** One {@code From} with two addresses, the customer's first. */
        TWO_ADDRESSES,
        /** The customer's address as the display name of another address. */
        ADDRESS_IN_DISPLAY_NAME
    }

    /** A case: the account whose issues must stay empty, the addresses in the mail, and the mail. */
    private record Case(long accountId, List<String> addresses, Inbound mail) {}

    @ParameterizedTest(name = "{0}")
    @EnumSource(Sender.class)
    @DisplayName("S12-AC-13 mail from no account, an agent, an admin, an invited, suspended, or anonymized customer, or with a From that is not one address is discarded: no issue, no mail to any address, only the masked address in the log")
    void discardedWithoutTrace(Sender sender) {
        Case c = setUp(sender);
        long mailsBefore = notInvitations();
        AppLog.Mark mark = log.mark();

        send(c.mail());
        rounds.next();

        assertThat(foldersOf(c.mail())).as("folders of the mail").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(c.accountId())).as("issues of the customer of the case").isEmpty();
        awaitALaterMail();
        assertThat(notInvitations()).as("mails sent other than invitations").isEqualTo(mailsBefore);
        List<String> lines = log.lines(mark);
        assertThat(lines).as("log lines of the round").anyMatch(line -> line.contains("UNKNOWN_SENDER"));
        for (String address : c.addresses()) {
            assertThat(lines).as("log lines with %s", address).noneMatch(line -> line.contains(address));
        }
    }

    private Case setUp(Sender sender) {
        String code = company().code();
        Inbound mail = Inbound.mail().text("Who " + Unique.number());
        return switch (sender) {
            case NO_ACCOUNT -> {
                Session customer = customer(code);
                String nobody = Unique.email("nobody");
                yield new Case(customer.accountId(), List.of(nobody), mail.from(nobody));
            }
            case AGENT -> {
                Session agent = agent(code);
                yield new Case(agent.accountId(), List.of(agent.email()), mail.from(agent.email()));
            }
            case ADMIN -> {
                Session other = fixtures.accept(invite(Role.ADMIN));
                yield new Case(other.accountId(), List.of(other.email()), mail.from(other.email()));
            }
            case INVITED -> {
                Invitation invited = invite(Role.CUSTOMER, code);
                yield new Case(invited.accountId(), List.of(invited.email()), mail.from(invited.email()));
            }
            case SUSPENDED -> {
                Session customer = customer(code);
                ManagerSteps.suspend(admin, customer.accountId());
                yield new Case(customer.accountId(), List.of(customer.email()), mail.from(customer.email()));
            }
            case ANONYMIZED -> {
                Session customer = customer(code);
                PrivacySteps.anonymize(admin, customer.accountId());
                yield new Case(customer.accountId(), List.of(customer.email()), mail.from(customer.email()));
            }
            case TWO_FROM_HEADERS -> {
                Session customer = customer(code);
                String nobody = Unique.email("nobody");
                yield new Case(customer.accountId(), List.of(nobody, customer.email()),
                        mail.from(nobody).from(customer.email()));
            }
            case TWO_ADDRESSES -> {
                Session customer = customer(code);
                String nobody = Unique.email("nobody");
                yield new Case(customer.accountId(), List.of(customer.email(), nobody),
                        mail.from(customer.email() + ", " + nobody));
            }
            case ADDRESS_IN_DISPLAY_NAME -> {
                Session customer = customer(code);
                String evil = Unique.email("evil");
                yield new Case(customer.accountId(), List.of(customer.email(), evil),
                        mail.from("\"" + customer.email() + "\" <" + evil + ">"));
            }
        };
    }

    /** How many mails the app has sent so far, invitations left out. */
    private long notInvitations() {
        return mailIn().sent().stream().filter(m -> !m.text().contains("/invite#token=")).count();
    }
}
