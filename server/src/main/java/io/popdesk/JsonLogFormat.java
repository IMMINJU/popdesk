package io.popdesk;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * The log format (PII-2, S0-AC-86): one JSON object per line on standard output, with the time, level,
 * logger, message, and stack trace. Set by {@code logging.structured.format.console}.
 *
 * <p>The last line of defense: before a line is written, everything that looks like an email address,
 * as is, percent-encoded ({@code %40}), or JSON-escaped (a backslash and {@code u0040}), is masked after
 * its first character, in the message and in the stack trace (S0 premortem 4). Code should not log emails in the
 * first place; this catches the ones that slip through, for example in an exception's message.
 */
public class JsonLogFormat implements StructuredLogFormatter<ILoggingEvent> {

    private static final Pattern EMAIL = Pattern.compile(
            "([\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]*(@|%40|%2540|\\\\{1,2}u0040)([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String format(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"time\":\"").append(Instant.ofEpochMilli(event.getTimeStamp())).append('"');
        out.append(",\"level\":\"").append(event.getLevel()).append('"');
        field(out, "logger", event.getLoggerName());
        field(out, "message", event.getFormattedMessage());
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            field(out, "exception", ThrowableProxyUtil.asString(thrown));
        }
        out.append("}\n");
        return mask(out.toString());
    }

    /** Masks every email-shaped text after its first character: {@code a***@example.com}. */
    static String mask(String text) {
        Matcher m = EMAIL.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + "***" + m.group(2) + m.group(3)));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(",\"").append(name).append("\":");
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
