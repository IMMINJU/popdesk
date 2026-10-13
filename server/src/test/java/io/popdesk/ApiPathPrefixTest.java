package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every path a controller maps is under one of the three API prefixes (ADR D-43, D-57).
 *
 * <p>The security filter chain gives a role to {@code /api/console/**} and {@code /api/portal/**},
 * opens {@code /api/public/**}, and ends with {@code anyRequest().permitAll()}. A controller mapped
 * anywhere else answers without a session. This test reads the mapping annotations of every
 * production controller class, as Spring does (class-level path joined with method-level path), and
 * fails for a path outside the three prefixes that is not in
 * {@code architecture/open-paths.txt}. No app is started.
 *
 * <p>Not seen here: paths added without a mapping annotation (view controllers, resource handlers,
 * servlets, filters that answer themselves). {@code ops.EndpointMappingsTest} holds the running
 * app's mappings outside {@code /api/} to GET and HEAD.
 */
class ApiPathPrefixTest {

    private static final String BASELINE = "/architecture/open-paths.txt";

    private static final List<String> PREFIXES = List.of("/api/portal/", "/api/console/", "/api/public/");

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.popdesk");

    @Test
    void noControllerPathOutsideTheThreePrefixes() {
        Set<String> paths = mappedPaths();
        Set<String> outside = new TreeSet<>();
        for (String path : paths) {
            if (PREFIXES.stream().noneMatch(path::startsWith)) {
                outside.add(path);
            }
        }

        assertThat(paths).as("paths the production controllers map").isNotEmpty();
        assertThat(Baseline.added(outside, BASELINE))
                .as("controller paths outside %s and not in %s: they answer without a session", PREFIXES, BASELINE)
                .isEmpty();
    }

    /** Every path pattern of every handler method, as written in the annotations. */
    private static Set<String> mappedPaths() {
        Set<String> out = new TreeSet<>();
        for (JavaClass imported : PRODUCTION) {
            if (!imported.isMetaAnnotatedWith(Controller.class) && !imported.isAnnotatedWith(Controller.class)) {
                continue;
            }
            Class<?> type = imported.reflect();
            RequestMapping onType = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = onType == null ? new String[0] : onType.path();
            Map<Method, RequestMapping> handlers = MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<RequestMapping>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
            for (RequestMapping onMethod : handlers.values()) {
                for (String prefix : prefixes.length == 0 ? new String[] {""} : prefixes) {
                    for (String path : onMethod.path().length == 0 ? new String[] {""} : onMethod.path()) {
                        out.add(prefix + path);
                    }
                }
            }
        }
        return out;
    }
}
