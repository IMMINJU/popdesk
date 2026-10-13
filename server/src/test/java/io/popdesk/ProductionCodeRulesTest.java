package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What production code must not hold (ADR D-57, design/process.md "막는 장치"): ways to wait, to
 * end the JVM, or to behave differently when a test is watching.
 *
 * <ul>
 *   <li>{@code calls X}: a call or method reference to {@code Thread.sleep}, {@code TimeUnit.sleep},
 *       {@code System.exit}, {@code Runtime.exit}, {@code Runtime.halt}, {@code getStackTrace} of a
 *       throwable or thread, {@code Thread.getAllStackTraces}, anything of {@code StackWalker},
 *       {@code System.getenv}, or the active-profile methods of Spring's {@code Environment}.
 *   <li>{@code @Profile}: the annotation on a class or method.
 *   <li>{@code name X}: a class, method, or field named as test-only ({@link #TEST_NAME}).
 *   <li>{@code mentions X}: a test library or the test clock in the code or in a string
 *       ({@link #TEST_WORD}).
 * </ul>
 *
 * <p>A finding is {@code class | form}, for the top-level class, once however often the class has
 * it, so the baseline {@code architecture/production-code-rules.txt} does not move with line
 * numbers. Calls, annotations, and names are read from bytecode; mentions from the sources with
 * comments blanked. Not seen here: a local variable's name, and a test-only branch that uses none of
 * these words (scripts/ci/check-fixture-literals.py reports fixture values in production code).
 */
class ProductionCodeRulesTest {

    private static final String BASELINE = "/architecture/production-code-rules.txt";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.popdesk");

    /** {@code isTest}, {@code forTesting}, {@code inTestMode}, {@code testMode}, {@code TestClock}, and the like. */
    static final Pattern TEST_NAME = Pattern.compile(
            "(?i)(is|in|for|under|during|when|running)_?(unit_?|integration_?)?tests?(ing)?"
                    + "(_?(mode|run|env|environment|profile|context|only))?"
                    + "|test_?(mode|only|profile|env|environment)|testing|TestClock");

    /** Words that belong to test code, in code or in a string. */
    static final Pattern TEST_WORD = Pattern.compile(
            "\\b(TestClock|org\\.junit|org\\.mockito|org\\.testcontainers|org\\.assertj|org\\.springframework\\.test"
                    + "|org\\.springframework\\.boot\\.test|io\\.popdesk\\.support)\\b");

    @Test
    void noForbiddenConstructOutsideTheBaseline() {
        Set<String> found = new TreeSet<>();
        int accesses = 0;
        for (JavaClass type : PRODUCTION) {
            String top = topLevel(type);
            List<JavaAccess<?>> calls = new ArrayList<>(type.getMethodCallsFromSelf());
            calls.addAll(type.getMethodReferencesFromSelf());
            for (JavaAccess<?> call : calls) {
                accesses++;
                String form = forbiddenCall((CodeUnitAccessTarget) call.getTarget());
                if (form != null) {
                    found.add(top + " | calls " + form);
                }
            }
            if (type.isAnnotatedWith("org.springframework.context.annotation.Profile")) {
                found.add(top + " | @Profile");
            }
            if (TEST_NAME.matcher(type.getSimpleName()).matches()) {
                found.add(top + " | name " + type.getSimpleName());
            }
            for (JavaMember member : type.getMembers()) {
                if (member.isAnnotatedWith("org.springframework.context.annotation.Profile")) {
                    found.add(top + " | @Profile");
                }
                if (TEST_NAME.matcher(member.getName()).matches()) {
                    found.add(top + " | name " + member.getName());
                }
            }
        }
        for (Map.Entry<String, String> file : ProductionSources.read(true).entrySet()) {
            Matcher m = TEST_WORD.matcher(file.getValue());
            while (m.find()) {
                found.add(ProductionSources.className(file.getKey()) + " | mentions " + m.group(1));
            }
        }

        assertThat(accesses).as("method calls read from the production classes").isPositive();
        assertThat(Baseline.added(found, BASELINE))
                .as("forbidden constructs in production code not in %s (all now: %s)", BASELINE, found)
                .isEmpty();
    }

    private static String topLevel(JavaClass type) {
        String name = type.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    /** The form of a forbidden call, such as {@code System.exit}, or null. */
    private static String forbiddenCall(CodeUnitAccessTarget target) {
        JavaClass owner = target.getOwner();
        String name = target.getName();
        if (owner.getName().startsWith("java.lang.StackWalker")) {
            return "StackWalker";
        }
        if (owner.isAssignableTo(Thread.class)) {
            if (name.equals("sleep") || name.equals("getStackTrace") || name.equals("getAllStackTraces")) {
                return "Thread." + name;
            }
        }
        if (owner.isAssignableTo(Throwable.class) && name.equals("getStackTrace")) {
            return "Throwable.getStackTrace";
        }
        if (owner.isEquivalentTo(java.util.concurrent.TimeUnit.class) && name.equals("sleep")) {
            return "TimeUnit.sleep";
        }
        if (owner.isEquivalentTo(System.class) && (name.equals("exit") || name.equals("getenv"))) {
            return "System." + name;
        }
        if (owner.isEquivalentTo(Runtime.class) && (name.equals("exit") || name.equals("halt"))) {
            return "Runtime." + name;
        }
        if (owner.isAssignableTo("org.springframework.core.env.Environment")
                && (name.equals("acceptsProfiles") || name.equals("matchesProfiles")
                        || name.equals("getActiveProfiles") || name.equals("getDefaultProfiles"))) {
            return "Environment." + name;
        }
        return null;
    }
}
