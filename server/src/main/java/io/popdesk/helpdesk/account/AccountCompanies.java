package io.popdesk.helpdesk.account;

import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Viewer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The companies as accounts need them: for invitations and for an account's own details. The company
 * package implements it, so that this package does not depend on that one (ADR D-57).
 */
public interface AccountCompanies {

    /** One company. */
    interface Company {
        long id();

        String code();

        String name();

        CompanyStatus status();
    }

    /** A company someone belongs to, by account. */
    interface Membership {
        long accountId();

        String code();

        String name();
    }

    /** The companies with these codes that the viewer may see. */
    List<? extends Company> findByCodes(Viewer viewer, Collection<String> codes);

    /** Makes a customer belong to a company; false when the customer belonged to it already. */
    boolean insertMember(long companyId, long accountId, Instant now);

    /** The companies that these customers belong to, among those the viewer may see. */
    List<? extends Membership> listMemberships(Viewer viewer, Collection<Long> accountIds);

    /**
     * The companies the viewer is tied to, whatever their status, in code order: the ones a customer
     * belongs to or an agent is assigned to. None for an admin.
     */
    List<? extends Company> listOwn(Viewer viewer);

    /** The codes of the companies where the viewer, a customer, is a manager (S2-AC-07). */
    List<String> listManagedCodes(Viewer viewer);
}
