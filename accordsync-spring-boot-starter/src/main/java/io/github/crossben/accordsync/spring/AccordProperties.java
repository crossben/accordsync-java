package io.github.crossben.accordsync.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code accord.*} properties.
 *
 * <pre>
 * accord.enabled=true                 # false: no Accord beans at all
 * accord.path-prefix=                 # "" (default): /v1/push, /v1/pull, /health; "/sync": /sync/v1/push ...
 * accord.migrate-on-startup=true      # run pending migrations when the server bean is created
 * accord.compaction.enabled=true      # background compaction every definition.compaction().intervalMs()
 * </pre>
 */
@ConfigurationProperties("accord")
public class AccordProperties {
    private boolean enabled = true;
    private String pathPrefix = "";
    private boolean migrateOnStartup = true;
    private final Compaction compaction = new Compaction();

    /** @return whether the starter is on */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether the starter is on */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the path the sync API is served under ("" or "/x", no trailing slash) */
    public String getPathPrefix() {
        return pathPrefix;
    }

    /** @param pathPrefix the path the sync API is served under */
    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
    }

    /** @return whether migrations run at startup */
    public boolean isMigrateOnStartup() {
        return migrateOnStartup;
    }

    /** @param migrateOnStartup whether migrations run at startup */
    public void setMigrateOnStartup(boolean migrateOnStartup) {
        this.migrateOnStartup = migrateOnStartup;
    }

    /** @return the compaction settings */
    public Compaction getCompaction() {
        return compaction;
    }

    /**
     * The normalised prefix: "" or "/a/b" (a trailing slash dropped).
     *
     * @return the prefix
     * @throws IllegalArgumentException when it does not start with "/"
     */
    public String normalizedPrefix() {
        String p = pathPrefix.trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.isEmpty() && !p.startsWith("/")) {
            throw new IllegalArgumentException("accord.path-prefix must be empty or start with \"/\": " + pathPrefix);
        }
        if (p.contains("*") || p.contains("?") || p.contains("#")) {
            throw new IllegalArgumentException("accord.path-prefix must be a plain path: " + pathPrefix);
        }
        return p;
    }

    /** {@code accord.compaction.*}. */
    public static class Compaction {
        private boolean enabled = true;

        /** @return whether background compaction runs (when the definition's interval is positive) */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether background compaction runs */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
