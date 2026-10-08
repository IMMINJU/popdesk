package io.popdesk.support;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Values that no other test in this JVM uses. A cached test context keeps its database for many
 * test classes, so fixtures never use fixed codes or emails.
 */
public final class Unique {

    private static final AtomicLong NEXT = new AtomicLong(1);

    private Unique() {}

    public static long number() {
        return NEXT.getAndIncrement();
    }

    /** A customer company code: capital letters and digits, 2 to 10 characters ({@code C0001}). */
    public static String companyCode() {
        return "C" + String.format(Locale.ROOT, "%4s", Long.toString(number(), 36)).replace(' ', '0')
                .toUpperCase(Locale.ROOT);
    }

    /** An email address on the reserved {@code example.test} domain: {@code customer-12@example.test}. */
    public static String email(String prefix) {
        return prefix + "-" + number() + "@example.test";
    }

    /** {@code prefix} with a unique number: {@code Issue 12}. */
    public static String text(String prefix) {
        return prefix + " " + number();
    }
}
