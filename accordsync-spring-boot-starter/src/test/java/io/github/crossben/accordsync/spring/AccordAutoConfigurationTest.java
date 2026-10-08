package io.github.crossben.accordsync.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.ServerDefinition;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class AccordAutoConfigurationTest {
    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AccordAutoConfiguration.class))
            .withPropertyValues("accord.migrate-on-startup=false");

    private WebApplicationContextRunner full() {
        return web.withBean(ServerDefinition.class, Stubs::definition)
                .withBean(DataSource.class, Stubs.DownDataSource::new);
    }

    @Test
    void speaksProtocolVersion1() {
        assertThat(AccordAutoConfiguration.PROTOCOL_VERSION).isEqualTo(1);
    }

    @Test
    void staysOffWithoutADefinition() {
        web.withBean(DataSource.class, Stubs.DownDataSource::new)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AccordServer.class).doesNotHaveBean(AccordProperties.class));
    }

    @Test
    void staysOffWithoutADataSource() {
        web.withBean(ServerDefinition.class, Stubs::definition)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AccordServer.class));
    }

    @Test
    void staysOffWhenDisabled() {
        full().withPropertyValues("accord.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AccordServer.class).doesNotHaveBean("accordServlet"));
    }

    @Test
    void wiresTheServerTheServletTheSchedulerAndTheHealthIndicator() {
        full().run(ctx -> {
            assertThat(ctx).hasSingleBean(AccordServer.class).hasSingleBean(AccordCompactionScheduler.class)
                    .hasSingleBean(AccordHealthIndicator.class);
            AccordServer server = ctx.getBean(AccordServer.class);
            assertThat(server.definition()).isSameAs(ctx.getBean(ServerDefinition.class));
            assertThat(server.dataSource()).isSameAs(ctx.getBean(DataSource.class));
            ServletRegistrationBean<?> reg = ctx.getBean("accordServlet", ServletRegistrationBean.class);
            assertThat(reg.getUrlMappings()).containsExactly("/v1/*", "/health");
            // Compaction.DEFAULT: every hour.
            assertThat(ctx.getBean(AccordCompactionScheduler.class).isRunning()).isTrue();
            assertThat(ctx.getBean(AccordCompactionScheduler.class).intervalMs()).isEqualTo(3_600_000L);
            // The database is down: the health indicator says so.
            assertThat(ctx.getBean(AccordHealthIndicator.class).health().getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void servesUnderAPathPrefix() {
        full().withPropertyValues("accord.path-prefix=/sync/").run(ctx -> {
            ServletRegistrationBean<?> reg = ctx.getBean("accordServlet", ServletRegistrationBean.class);
            assertThat(reg.getUrlMappings()).containsExactly("/sync/v1/*", "/sync/health");
        });
    }

    @Test
    void refusesARelativePrefix() {
        full().withPropertyValues("accord.path-prefix=sync").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void compactionCanBeTurnedOff() {
        full().withPropertyValues("accord.compaction.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(AccordServer.class).doesNotHaveBean(AccordCompactionScheduler.class));
    }

    @Test
    void noBackgroundCompactionWhenTheIntervalIsZero() {
        web.withBean(ServerDefinition.class, () -> io.github.crossben.accordsync.server.AccordServer.define(d -> d
                        .schema(Stubs.definition().schema())
                        .scope("note", r -> java.util.List.of("all"))
                        .access(c -> io.github.crossben.accordsync.server.Access.readWrite("all"))
                        .auth(io.github.crossben.accordsync.server.Auth.hs256("0123456789abcdef0123456789abcdef"))
                        .compaction(new io.github.crossben.accordsync.server.Compaction(30, 0, 2))))
                .withBean(DataSource.class, Stubs.DownDataSource::new)
                .run(ctx -> assertThat(ctx.getBean(AccordCompactionScheduler.class).isRunning()).isFalse());
    }

    @Test
    void migratesAtStartupByDefault() {
        Stubs.DownDataSource db = new Stubs.DownDataSource();
        web.withPropertyValues("accord.migrate-on-startup=true")
                .withBean(ServerDefinition.class, Stubs::definition)
                .withBean(DataSource.class, () -> db)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(db.attempts).isPositive();
                });
    }

    @Test
    void anAppServerBeanWins() {
        AccordServer mine = new AccordServer(Stubs.definition(), new Stubs.DownDataSource());
        full().withBean("myServer", AccordServer.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(AccordServer.class)).isSameAs(mine));
    }

    @Test
    void noServletOutsideAServletWebApp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AccordAutoConfiguration.class))
                .withPropertyValues("accord.migrate-on-startup=false")
                .withBean(ServerDefinition.class, Stubs::definition)
                .withBean(DataSource.class, Stubs.DownDataSource::new)
                .run(ctx -> assertThat(ctx).hasSingleBean(AccordServer.class).doesNotHaveBean("accordServlet"));
    }

    @Test
    void noHealthIndicatorWithoutTheHealthModule() {
        full().withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(AccordServer.class).doesNotHaveBean(AccordHealthIndicator.class));
    }
}
