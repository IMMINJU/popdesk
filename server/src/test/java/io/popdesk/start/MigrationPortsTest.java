package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Wait;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * While migrations run, neither port is open: health checks get "connection refused" until the
 * migrations and the first admin are done. Fixed free ports stand in for 8080 and 8081, so the test
 * knows where to knock before the app says where it listens.
 */
class MigrationPortsTest extends OwnStartTest {

    @Test
    @DisplayName("S0-AC-09 during a slow migration both ports refuse connections; readiness is UP once the admin exists")
    void portsClosedWhileMigrating() {
        Database db = postgres.newDatabase();
        int httpPort = freePort();
        int managementPort = freePort();
        Settings settings = Settings.firstInstall(db)
                .withFixtureMigrations("slow")
                .set("server.port", Integer.toString(httpPort))
                .set("management.server.port", Integer.toString(managementPort));

        Boot.Background start = Boot.startInBackground(settings);
        try {
            Wait.until("the slow fixture migration running", () -> db.running("pg_sleep") ? true : null);
            assertThat(refuses(httpPort)).as("HTTP port %s refuses connections while migrating", httpPort).isTrue();
            assertThat(refuses(managementPort)).as("management port %s refuses connections while migrating",
                    managementPort).isTrue();

            Boot app = start.await(START).assertStarted();
            assertThat(app.httpPort()).isEqualTo(httpPort);
            assertThat(app.managementPort()).isEqualTo(managementPort);
            assertReady(app);
            assertOneAccountRow(db, Settings.ADMIN_EMAIL);
        } finally {
            start.close(db);
        }
    }

    private static boolean refuses(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (IOException other) {
            throw new AssertionError("Connecting to port " + port + " failed in another way", other);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
