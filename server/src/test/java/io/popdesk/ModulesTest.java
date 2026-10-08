package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries (design/spec/architecture.md section 3, ADR D-6). */
class ModulesTest {

    static final ApplicationModules MODULES = ApplicationModules.of(PopdeskApplication.class);

    @Test
    void modulesAreHelpdeskAndNotification() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("helpdesk", "notification");
    }

    @Test
    void boundariesHold() {
        MODULES.verify();
    }
}
