package io.popdesk.ops;

import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;

/**
 * Two companies and the people around them, for checks that need a target in and out of scope:
 * company A with two customers and an assigned agent, company B with one customer, an agent
 * assigned nowhere, and a public issue of A's first customer with an attachment.
 */
record OpsWorld(Session admin, Company a, Company b, Session customerA, Session colleagueA, Session customerB,
        Session agentA, Session agentNowhere, Invitation invitedAgent, Issue issueA, Attachment attachmentA) {

    static OpsWorld build(Fixtures fixtures) {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session customerA = fixtures.customer(admin, a.code());
        Session colleagueA = fixtures.customer(admin, a.code());
        Session customerB = fixtures.customer(admin, b.code());
        Session agentA = fixtures.agent(admin, a.code());
        Session agentNowhere = fixtures.agent(admin);
        Invitation invitedAgent = fixtures.invite(admin, Role.AGENT);
        Issue issueA = fixtures.fileIssue(customerA, a.code());
        Attachment attachmentA = fixtures.attach(customerA, issueA.key(), File.text("notes.txt", "Notes of A."));
        return new OpsWorld(admin, a, b, customerA, colleagueA, customerB, agentA, agentNowhere, invitedAgent,
                issueA, attachmentA);
    }

    /** {@code /issues/{key}} of A's issue under the session's app. */
    String issuePath(Session session) {
        return session.api("/issues/" + issueA.key());
    }

    String attachmentPath(Session session) {
        return issuePath(session) + "/attachments/" + attachmentA.id();
    }
}
