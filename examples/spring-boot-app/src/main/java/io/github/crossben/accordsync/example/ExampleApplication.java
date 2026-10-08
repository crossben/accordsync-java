package io.github.crossben.accordsync.example;

import io.github.crossben.accordsync.server.ServerDefinition;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The whole integration: one {@link ServerDefinition} bean. The starter does the rest (the server on
 * the app's HikariCP DataSource, migrations, /v1/push, /v1/pull, /health, compaction, the "accord"
 * health indicator). This app's definition is the conformance profile, so the shared conformance
 * suite can run against it; a real app writes its own schema, scopes and access rules.
 */
@SpringBootApplication
public class ExampleApplication {
    /** Created by Spring. */
    public ExampleApplication() {}

    /**
     * The server definition.
     *
     * @param profile the conformance profile.json
     * @return the definition
     * @throws IOException when the profile cannot be read
     */
    @Bean
    public ServerDefinition accordDefinition(@Value("${accord.example.profile}") String profile) throws IOException {
        return ConformanceProfile.load(Path.of(profile)).definition();
    }

    /**
     * Starts the app.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
