package io.popdesk.helpdesk.issue;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An issue key {@code {company code}-{number}}, for example {@code ACME-7} (ISSUE-3). */
public record IssueKey(String companyCode, int number) {

    private static final Pattern KEY = Pattern.compile("([A-Z0-9]{2,10})-([1-9][0-9]{0,8})");

    /** The key in a path; empty when it cannot be a key, which the caller answers like an unknown one (404). */
    public static Optional<IssueKey> parse(String key) {
        Matcher m = key == null ? null : KEY.matcher(key);
        if (m == null || !m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new IssueKey(m.group(1), Integer.parseInt(m.group(2))));
    }

    @Override
    public String toString() {
        return companyCode + "-" + number;
    }
}
