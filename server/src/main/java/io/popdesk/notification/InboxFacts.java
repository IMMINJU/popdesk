package io.popdesk.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the inbox reads from helpdesk when it is read (INBOX-1, INBOX-3): which of the issues its items
 * are about the account may see now, what those issues are called now, and who did what the items are
 * about. Helpdesk owns the issues and the accounts and implements this; notification reads none of
 * their tables.
 */
public interface InboxFacts {

    /** Who reads the inbox: a customer on the portal, or staff on the console. */
    enum Reader {
        CUSTOMER,
        STAFF
    }

    /** What a person is, as the reader may know it. */
    enum ActorKind {
        CUSTOMER,
        AGENT,
        ADMIN,
        /** Any agent or admin, for a customer (ADR D-49). */
        SUPPORT_TEAM
    }

    /**
     * Who did something, already as the reader may see them. For {@link ActorKind#SUPPORT_TEAM} the
     * account ID, the name, and the photo time are all null.
     *
     * @param photoUpdatedAt when the profile photo last changed; null without a photo
     */
    record Actor(ActorKind kind, @Nullable Long accountId, @Nullable String name, @Nullable Instant photoUpdatedAt) {}

    /** Of these issues, the ones the account may see now, with the same scope as the API (S4-AC-42). */
    Set<UUID> visible(long accountId, Collection<UUID> issueIds);

    /** The key, the title as it is now, and the company code of each of these issues that exists. */
    Map<UUID, IssueFacts.Issue> issues(Collection<UUID> issueIds);

    /**
     * Each of these accounts that exists, as the reader may see it: staff get the person; a customer
     * gets a customer as the person and staff as the support team, without name or ID (S4-AC-41).
     */
    Map<Long, Actor> actors(Collection<Long> accountIds, Reader reader);

    /**
     * The title as it is now of each of these tasks that exists and is not deleted (S6B-AC-33). Staff
     * only: a customer's inbox has no item about a task.
     */
    Map<Long, String> taskTitles(Collection<Long> taskIds);
}
