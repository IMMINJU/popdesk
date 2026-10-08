package io.popdesk;

import org.springframework.boot.SpringApplication;

/** Runs the app on a Testcontainers database for local work: {@code ./mvnw spring-boot:test-run}. */
public class TestPopdeskApplication {

    public static void main(String[] args) {
        SpringApplication.from(PopdeskApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
