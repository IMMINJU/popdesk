package io.popdesk.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console contract and the role check agree on what is for admins only (ADR D-43, D-57,
 * docs/use/permissions.md). The operations are read from {@code docs/integrate/openapi-console.json},
 * not listed by hand: an operation is for admins only when the description of its 403 answer holds
 * {@code admins only}. An agent calls every operation under {@code /api/console/}:
 *
 * <ul>
 *   <li>one for admins only answers 403 {@code FORBIDDEN}, so a new admin endpoint left out of the
 *       role check fails here;
 *   <li>any other does not answer 403 {@code FORBIDDEN}, so a path the role check closes to agents
 *       without the contract saying so fails here.
 * </ul>
 *
 * <p>Path variables are filled with values that name nothing and no body is sent. The role check
 * answers before the target is looked up and before the body is read (conventions "Order of
 * checks"), and for the other operations a missing target is 404, never 403.
 */
class AdminOnlyContractTest extends BareAppTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head");

    private static final String CONSOLE = "/api/console/";

    /** One console operation of the contract. */
    private record Operation(String method, String path, boolean adminOnly) {

        /** The path with every variable filled with a value that names nothing. */
        String target() {
            return path.replace("{code}", "ZZ9NOPE").replace("{key}", "NOPE-999999")
                    .replaceAll("\\{[^}]*}", "999999999");
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static List<Operation> consoleOperations() {
        Path file = Path.of("..", "docs", "integrate", "openapi-console.json");
        JsonNode paths;
        try {
            paths = JsonMapper.builder().build().readTree(Files.readString(file)).path("paths");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
        }
        List<Operation> out = new ArrayList<>();
        for (String path : paths.propertyNames()) {
            if (!path.startsWith(CONSOLE)) {
                continue;
            }
            for (String method : paths.path(path).propertyNames()) {
                if (METHODS.contains(method)) {
                    String forbidden = paths.path(path).path(method).path("responses").path("403")
                            .path("description").asString("");
                    out.add(new Operation(method.toUpperCase(Locale.ROOT), path,
                            forbidden.toLowerCase(Locale.ROOT).contains("admins only")));
                }
            }
        }
        return out;
    }

    private static boolean forbidden(Operation operation, ApiResponse response) {
        if (response.status() != 403) {
            return false;
        }
        // A HEAD answer has no body to read the code from.
        return operation.method().equals("HEAD") || "FORBIDDEN".equals(response.json().path("code").asString(""));
    }

    @Test
    void agentGets403OnEveryOperationTheContractMarksAdminsOnly() {
        List<Operation> adminOnly = consoleOperations().stream().filter(Operation::adminOnly).toList();
        Session agent = fixtures.agent(fixtures.signInAdmin());

        assertThat(adminOnly).as("console operations whose 403 says admins only").isNotEmpty();
        SoftAssertions soft = new SoftAssertions();
        for (Operation operation : adminOnly) {
            ApiResponse response = agent.client().request(operation.method(), operation.target()).send();
            soft.assertThat(forbidden(operation, response))
                    .as("%s is for admins only in the contract; an agent got %s %s", operation, response.status(),
                            response.snippet())
                    .isTrue();
        }
        soft.assertAll();
    }

    @Test
    void agentIsNotRefusedByRoleOnAnyOtherConsoleOperation() {
        List<Operation> others = consoleOperations().stream().filter(o -> !o.adminOnly()).toList();
        Session agent = fixtures.agent(fixtures.signInAdmin());

        assertThat(others).as("console operations for agents and admins").isNotEmpty();
        SoftAssertions soft = new SoftAssertions();
        for (Operation operation : others) {
            ApiResponse response = agent.client().request(operation.method(), operation.target()).send();
            soft.assertThat(forbidden(operation, response))
                    .as("%s is not for admins only in the contract; an agent got %s %s", operation,
                            response.status(), response.snippet())
                    .isFalse();
        }
        soft.assertAll();
    }
}
