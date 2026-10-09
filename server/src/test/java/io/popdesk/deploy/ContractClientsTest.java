package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract files and the clients made from them (ADR D-21, D-43). The real checks are elsewhere
 * and are not repeated here: {@code OpenApiFilesTest} writes the files from the running app with
 * {@code ./mvnw -Popenapi test}, and the CI server job fails when they differ from the committed
 * ones; the CI web job runs {@code pnpm gen:api} and fails when the generated clients differ. This
 * test only shows, without those jobs, that each committed client covers every path of its file.
 * It passes before the slice is built because the contract step (C-4) already made both.
 */
@Tag("deploy")
class ContractClientsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "S0-AC-91 [{0}] the generated client has every path of the contract file")
    @ValueSource(strings = {"portal", "console"})
    @DisplayName("S0-AC-91 each generated client in web/packages/api has every path of its contract file")
    void clientCoversContract(String app) throws IOException {
        String contract = Files.readString(Repo.require("docs/integrate/openapi-" + app + ".json", "contract file"),
                StandardCharsets.UTF_8);
        String client = Files.readString(Repo.require("web/packages/api/src/generated/" + app + ".ts",
                "client generated from the contract file"), StandardCharsets.UTF_8);
        List<String> paths = new ArrayList<>();
        JSON.readTree(contract).path("paths").propertyNames().forEach(paths::add);

        assertThat(paths).as("paths of openapi-%s.json", app).isNotEmpty();
        for (String path : paths) {
            assertThat(client).as("%s in generated/%s.ts", path, app).contains("\"" + path + "\"");
        }
    }
}
