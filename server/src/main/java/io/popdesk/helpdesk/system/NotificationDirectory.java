package io.popdesk.helpdesk.system;

import io.popdesk.PublicAddress.App;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.notification.IssueFacts;
import io.popdesk.notification.Recipients;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the notification module reads about accounts and issues when it sends (MAIL-5, S0-AC-66): the
 * address, name, language, app, and status of an account, the active admins, and an issue's key, title,
 * and company code. A system read: no viewer, by ID; who may see an issue is {@link IssueVisibility}.
 * An issue in the trash or purged is none: what waits to be sent about it is dropped (S7A-AC-10).
 */
@Component
class NotificationDirectory implements Recipients, IssueFacts {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    NotificationDirectory(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Recipient> find(long accountId) {
        return accounts.findById(accountId).map(NotificationDirectory::recipient);
    }

    @Override
    public List<Long> activeAdmins() {
        return jdbc.sql("select id from account where role = ? and status = ? order by id")
                .params(Role.ADMIN.name(), AccountStatus.ACTIVE.name()).query(Long.class).list();
    }

    @Override
    public Optional<Issue> find(UUID issueId) {
        return jdbc.sql("select c.code, i.number, i.title from issue i join company c on c.id = i.company_id "
                        + "where i.id = ? and i.deleted_at is null")
                .param(issueId)
                .query((rs, n) -> new Issue(rs.getString("code") + "-" + rs.getInt("number"), rs.getString("title"),
                        rs.getString("code")))
                .optional();
    }

    private static Recipient recipient(AccountRow a) {
        Status status = switch (a.status()) {
            case INVITED -> Status.INVITED;
            case ACTIVE -> Status.ACTIVE;
            case SUSPENDED -> Status.INACTIVE;
        };
        return new Recipient(a.email(), a.name(), a.locale(), a.role() == Role.CUSTOMER ? App.PORTAL : App.CONSOLE,
                status);
    }
}
