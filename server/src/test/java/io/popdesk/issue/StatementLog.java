package io.popdesk.issue;

import io.popdesk.support.DbInspector;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL server log of a context whose database logs every statement
 * ({@code log_statement=all}). Import {@link Config} into a test class to get such a context; it is
 * a context of its own, apart from the named setups.
 *
 * <p>A window of the log is marked by two queries with unique text, run on {@link DbInspector}'s
 * connection. Everything the server logged between them, from any connection, is in the window.
 * Each log line starts with the server process ID ({@code log_line_prefix=[%p] }), so
 * {@link #byProcess} can split a window into what each connection did.
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

    private static final Pattern PREFIX = Pattern.compile("^\\[(\\d+)] ");

    /**
     * The lines of a window by server process (one per database connection), in log order. A line
     * without the prefix continues the line before it, as a statement spread over several lines does.
     */
    static Map<String, List<String>> byProcess(String window) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String pid = null;
        for (String line : window.split("\\R")) {
            Matcher m = PREFIX.matcher(line);
            if (m.find()) {
                pid = m.group(1);
                out.computeIfAbsent(pid, k -> new ArrayList<>()).add(line.substring(m.end()));
            } else if (pid != null) {
                List<String> lines = out.get(pid);
                lines.set(lines.size() - 1, lines.getLast() + "\n" + line);
            }
        }
        return out;
    }
}
