package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How long a browser may keep the screen files (verify.md section 6). The files are in the image
 * only (the build of each web app), so this runs the image: {@code index.html} is asked for again
 * every time, and the files it points to under {@code assets/}, whose names carry a hash of their
 * content, are kept for a year.
 */
@Tag("deploy")
class ScreenFilesCacheTest {

    private static final Pattern ASSET = Pattern.compile("[\"'(](/?assets/[^\"')?#\\s]+)");

    private static final Once<Stack> RUNNING = new Once<>("starting the image", ScreenFilesCacheTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    private static Stack start() {
        Stack stack = Stack.release("screens");
        try {
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static ApiClient client(Stack stack, String app) {
        return app.equals("portal") ? stack.portal() : stack.console();
    }

    private static List<String> cacheControl(ApiResponse response) {
        String header = response.header("Cache-Control");
        assertThat(header).as("Cache-Control of %s", response).isNotNull();
        return Arrays.stream(header.split(",")).map(d -> d.strip().toLowerCase(Locale.ROOT)).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "console"})
    @DisplayName("S1A-AC-61 index.html is answered with Cache-Control no-cache: at /, at /index.html, and at a "
            + "screen path of the app's router")
    void indexIsAskedForEveryTime(String app) {
        ApiClient client = client(RUNNING.get(), app);

        for (String path : List.of("/", "/index.html", "/login")) {
            ApiResponse page = client.get(path).header("Accept", "text/html").send();

            assertThat(page.status()).as("%s: %s", page, page.snippet()).isEqualTo(200);
            assertThat(page.header("Content-Type")).as("Content-Type of %s", page).startsWith("text/html");
            assertThat(cacheControl(page)).as("Cache-Control of %s", page).containsExactly("no-cache");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "console"})
    @DisplayName("S1A-AC-61 every file under assets/ that index.html points to is answered with Cache-Control "
            + "public, max-age=31536000, immutable")
    void hashedAssetsAreKeptForAYear(String app) {
        ApiClient client = client(RUNNING.get(), app);
        ApiResponse index = client.get("/").header("Accept", "text/html").expect(200);
        List<String> assets = ASSET.matcher(index.text()).results().map(m -> m.group(1))
                .map(p -> p.startsWith("/") ? p : "/" + p).distinct().toList();
        assertThat(assets).as("assets/ files named in index.html of the %s: %s", app, index.snippet()).isNotEmpty();

        for (String path : assets) {
            ApiResponse file = client.get(path).send();

            assertThat(file.status()).as("%s: %s", file, file.snippet()).isEqualTo(200);
            assertThat(file.body().length).as("size of %s", file).isPositive();
            assertThat(cacheControl(file)).as("Cache-Control of %s", file)
                    .containsExactlyInAnyOrder("public", "max-age=31536000", "immutable");
        }
    }
}
