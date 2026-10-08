package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.ServerDefinition;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Accord server for Spring Boot. Active when the app defines a {@link ServerDefinition} bean
 * and has a {@link DataSource} (and {@code accord.enabled} is not false). Beans: the
 * {@link AccordServer} (migrated at startup unless {@code accord.migrate-on-startup=false}), the
 * background {@link AccordCompactionScheduler} ({@code accord.compaction.enabled}), the
 * {@link AccordServlet} on {@code <prefix>/v1/*} and {@code <prefix>/health} in a servlet web app,
 * and the "accord" Actuator health indicator when Spring Boot's health module is present.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(AccordServer.class)
@ConditionalOnProperty(prefix = "accord", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({ServerDefinition.class, DataSource.class})
@EnableConfigurationProperties(AccordProperties.class)
public class AccordAutoConfiguration {
    private static final System.Logger LOG = System.getLogger(AccordAutoConfiguration.class.getName());

    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = AccordServer.PROTOCOL_VERSION;

    /** Created by Spring. */
    public AccordAutoConfiguration() {}

    /**
     * The server, on the app's data source; runs pending migrations first unless disabled.
     *
     * @param definition the app's definition
     * @param dataSource the app's data source (HikariCP by default)
     * @param properties the properties
     * @return the server
     * @throws SQLException when a migration fails
     */
    @Bean
    @ConditionalOnMissingBean
    public AccordServer accordServer(ServerDefinition definition, DataSource dataSource, AccordProperties properties)
            throws SQLException {
        AccordServer server = new AccordServer(definition, dataSource);
        if (properties.isMigrateOnStartup()) {
            List<String> ran = server.migrate();
            if (!ran.isEmpty()) LOG.log(System.Logger.Level.INFO, "accord: migrated {0}", ran);
        }
        return server;
    }

    /**
     * Background compaction.
     *
     * @param server the server
     * @return the scheduler
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "accord.compaction", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AccordCompactionScheduler accordCompactionScheduler(AccordServer server) {
        return new AccordCompactionScheduler(server);
    }

    /** The endpoints, in a servlet web application. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServlet")
    static class Web {
        @Bean
        @ConditionalOnMissingBean(name = "accordServlet")
        ServletRegistrationBean<AccordServlet> accordServlet(AccordServer server, AccordProperties properties) {
            String prefix = properties.normalizedPrefix();
            ServletRegistrationBean<AccordServlet> bean = new ServletRegistrationBean<>(
                    new AccordServlet(server, prefix), prefix + "/v1/*", prefix + "/health");
            bean.setName("accordServlet");
            bean.setLoadOnStartup(1);
            return bean;
        }
    }

    /** The "accord" health indicator, when Spring Boot's health module (Actuator) is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class Health {
        @Bean
        @ConditionalOnMissingBean(name = "accordHealthIndicator")
        AccordHealthIndicator accordHealthIndicator(AccordServer server) {
            return new AccordHealthIndicator(server);
        }
    }
}
