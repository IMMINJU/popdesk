package io.popdesk.boundary;

import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;

/**
 * The two-company fixture of the boundary tests, made through the API: companies A and B, two customers in
 * each, one agent assigned to A only, another agent assigned to B only (so B has an assignment the
 * agent of A must not see through), and one admin. In each company the first customer files a
 * public and a private issue, each with a file; on the public issue staff add a public comment and
 * an internal comment, each with a file. Staff on A is the agent; on B, where the agent is not
 * assigned, the admin.
 *
 * <p>Titles are wrapped in {@code #...#} so no title is a prefix of another when searched as text.
 */
record CompanyWorld(Session admin, Session agentOfA, Session agentOfB, Side a, Side b) {

    /** One company and what is in it. */
    record Side(
            Company company,
            Session reporter,
            Session colleague,
            Issue publicIssue,
            Attachment publicIssueFile,
            Issue privateIssue,
            Attachment privateIssueFile,
            Comment publicComment,
            Attachment publicCommentFile,
            Comment internalComment,
            Attachment internalCommentFile) {

        String code() {
            return company.code();
        }

        String publicTitle() {
            return publicIssue.json().path("title").asString(null);
        }

        String privateTitle() {
            return privateIssue.json().path("title").asString(null);
        }

        String internalText() {
            return internalComment.json().path("content").asString(null);
        }
    }

    static CompanyWorld build(Fixtures fixtures) {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, a.code());
        Session agentOfB = fixtures.agent(admin, b.code());
        return new CompanyWorld(admin, agent, agentOfB, side(fixtures, admin, a, agent),
                side(fixtures, admin, b, admin));
    }

    private static Side side(Fixtures fixtures, Session admin, Company company, Session staff) {
        String code = company.code();
        Session reporter = fixtures.customer(admin, code);
        Session colleague = fixtures.customer(admin, code);
        Issue publicIssue = fixtures.fileIssue(reporter, NewIssue.in(code).title(title("Public", code)));
        Attachment publicIssueFile = fixtures.attach(reporter, publicIssue.key(), file("public-issue", code));
        Issue privateIssue = fixtures.fileIssue(reporter,
                NewIssue.in(code).title(title("Private", code)).privateIssue());
        Attachment privateIssueFile = fixtures.attach(reporter, privateIssue.key(), file("private-issue", code));
        Comment publicComment = fixtures.comment(staff, publicIssue.key(), "Public answer #" + Unique.number() + "#");
        Attachment publicCommentFile = fixtures.attachToComment(staff, publicIssue.key(), publicComment.id(),
                file("public-comment", code));
        Comment internalComment = fixtures.internalComment(staff, publicIssue.key(),
                "Internal note #" + Unique.number() + "#");
        Attachment internalCommentFile = fixtures.attachToComment(staff, publicIssue.key(), internalComment.id(),
                file("internal-comment", code));
        return new Side(company, reporter, colleague, publicIssue, publicIssueFile, privateIssue, privateIssueFile,
                publicComment, publicCommentFile, internalComment, internalCommentFile);
    }

    private static String title(String kind, String code) {
        return kind + " issue of " + code + " #" + Unique.number() + "#";
    }

    private static File file(String kind, String code) {
        long n = Unique.number();
        return File.text(kind + "-" + code + "-" + n + ".txt", kind + " file " + n + " of " + code);
    }
}
