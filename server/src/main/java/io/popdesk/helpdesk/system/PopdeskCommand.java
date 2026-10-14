package io.popdesk.helpdesk.system;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;

/**
 * The {@code popdesk} command inside the app container (ADR D-46, configuration.md "Commands"):
 * {@code backup <file>}, {@code restore <file>}, {@code admin reset-password <email>}, and
 * {@code attachments check}. It runs in a
 * JVM of its own next to the app, without Spring: it never takes the app's single-instance lock (only
 * {@code restore} checks it), never runs migrations, and opens no port.
 *
 * <p>A command that fails throws {@link Failed}; the {@code java} launcher then ends with exit code 1,
 * and the handler set here prints only the reason, never a stack trace or a value from the database.
 *
 * <p>{@code attachments check} also ends with 2, when it looked at every file and one is missing or of
 * another size (S5B-AC-42). A JVM ends with 0 or 1 by itself, so this entry point, and nothing else in
 * production code, calls {@code System.exit}: a command returns its exit code and never ends the JVM.
 */
public final class PopdeskCommand {

    /** Why a command stopped. The message is for the operator and holds no secret. */
    static final class Failed extends RuntimeException {

        Failed(String message) {
            super(message, null, false, false);
        }
    }

    static final String USAGE = """
            Usage:
              popdesk backup <file>                  write a database dump (pg_dump custom format)
              popdesk restore <file>                 restore a dump into an empty database, before the app starts
              popdesk admin reset-password <email>   set a new password for an admin account (needs a terminal)
              popdesk attachments check              check that the file of every attachment is where it was stored""";

    private PopdeskCommand() {}

    public static void main(String[] args) throws SQLException {
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> System.err.println("popdesk: " + reason(e)));
        Clock clock = Clock.systemUTC();
        CommandDatabase database = CommandDatabase.fromEnvironment(System.getenv());
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        int exit = 0;
        switch (args.length == 0 ? "" : args[0]) {
            case "backup" -> new BackupCommand(database, clock).run(single(rest, "backup <file>"));
            case "restore" -> new RestoreCommand(database, clock).run(single(rest, "restore <file>"));
            case "admin" -> {
                if (rest.length != 2 || !rest[0].equals("reset-password")) {
                    throw new Failed("expected admin reset-password <email>\n" + USAGE);
                }
                new ResetPasswordCommand(database, clock).run(rest[1]);
            }
            case "attachments" -> {
                if (rest.length != 1 || !rest[0].equals("check")) {
                    throw new Failed("expected attachments check\n" + USAGE);
                }
                exit = new AttachmentsCheckCommand(database, System.getenv()).run(System.out);
            }
            default -> throw new Failed(args.length == 0 ? USAGE : "unknown command " + args[0] + "\n" + USAGE);
        }
        if (exit != 0) {
            System.out.flush();
            System.exit(exit);
        }
    }

    private static String single(String[] rest, String usage) {
        if (rest.length != 1 || rest[0].isBlank()) {
            throw new Failed("expected " + usage);
        }
        return rest[0];
    }

    /** The reason to print: a {@link Failed} message, else the kind of error and SQL state only. */
    static String reason(Throwable e) {
        if (e instanceof Failed) {
            return e.getMessage();
        }
        if (e instanceof SQLException sql) {
            return "database error (SQL state " + sql.getSQLState() + ")";
        }
        return "unexpected error (" + e.getClass().getSimpleName() + ")";
    }
}
