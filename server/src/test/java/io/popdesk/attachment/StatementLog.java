package io.popdesk.attachment;

import io.popdesk.support.DbInspector;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL server log of a context whose database logs every statement
 * ({@code log_statement=all}), with bind parameters on {@code DETAIL} lines. The same tool as
 * {@code io.popdesk.issue.StatementLog}, which is package-private there; this copy also keeps the
 * order of statements across connections ({@link #entries}).
 *
 * <p>A window of the log is marked by two queries with unique text, run on {@link DbInspector}'s
 * connection. Everything the server logged between them, from any connection, is in the window.
 */
final class StatementLog {

    /** Starts the context's PostgreSQL container with {@code log_statement=all}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        static BeanPostProcessor postgresLogsEveryStatement() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof PostgreSQLContainer postgres && !postgres.isRunning()) {
                        postgres.setCommand("postgres", "-c", "fsync=off", "-c", "log_statement=all",
                                "-c", "log_line_prefix=[%p] ");
                    }
                    return bean;
                }
            };
        }
    }

    /** A started window: the text of its opening query. */
    record Window(String start) {}

    /**
     * One statement as the server logged it, by server process (one per connection), with its
     * {@code DETAIL} lines (bind parameters) joined on.
     */
    record Entry(int index, String pid, String text) {

        boolean contains(String value) {
            return text.contains(value);
        }

        boolean matches(Pattern pattern) {
            return pattern.matcher(text).find();
        }
    }

    private static final Pattern PREFIX = Pattern.compile("^\\[(\\d+)] ");

    private final DbInspector db;
    private final PostgreSQLContainer postgres;

    StatementLog(DbInspector db, PostgreSQLContainer postgres) {
        this.db = db;
        this.postgres = postgres;
    }

    /** Fails with an AssertionError unless the server logs every statement. */
    void assertLogsEveryStatement() {
        Object setting = db.row("select current_setting('log_statement') as s").get("s");
        if (!"all".equals(setting)) {
            throw new AssertionError("log_statement is " + setting + ", not all");
        }
    }

    Window start() {
        String marker = "popdesk-log-start-" + UUID.randomUUID();
        db.rows("select '" + marker + "' as marker");
        Wait.until("the start marker in the PostgreSQL log", () -> postgres.getLogs().contains(marker) ? marker : null);
        return new Window(marker);
    }

    /** The log from the window's start up to now. Waits until the server has written up to now. */
    String end(Window window) {
        String marker = "popdesk-log-end-" + UUID.randomUUID();
        db.rows("select '" + marker + "' as marker");
        String logs = Wait.until("the end marker in the PostgreSQL log", () -> {
            String all = postgres.getLogs();
            return all.contains(marker) ? all : null;
        });
        int from = logs.indexOf(window.start());
        int to = logs.indexOf(marker);
        if (from < 0 || to < from) {
            throw new AssertionError("The PostgreSQL log does not hold the window " + window.start());
        }
        return logs.substring(from, to);
    }

    /**
     * The statements of a window in log order. A line without the prefix continues the line before
     * it of the same process (a statement over several lines); a {@code DETAIL:} line is joined to
     * the statement before it of the same process.
     */
    static List<Entry> entries(String window) {
        List<String[]> raw = new ArrayList<>();
        for (String line : window.split("\\R")) {
            Matcher m = PREFIX.matcher(line);
            if (m.find()) {
                raw.add(new String[] {m.group(1), line.substring(m.end())});
            } else if (!raw.isEmpty()) {
                String[] last = raw.getLast();
                last[1] = last[1] + "\n" + line;
            }
        }
        List<Entry> out = new ArrayList<>();
        for (String[] line : raw) {
            if (line[1].startsWith("DETAIL:")) {
                for (int i = out.size() - 1; i >= 0; i--) {
                    Entry e = out.get(i);
                    if (e.pid().equals(line[0])) {
                        out.set(i, new Entry(e.index(), e.pid(), e.text() + "\n" + line[1]));
                        break;
                    }
                }
                continue;
            }
            out.add(new Entry(out.size(), line[0], line[1]));
        }
        return out;
    }
}
