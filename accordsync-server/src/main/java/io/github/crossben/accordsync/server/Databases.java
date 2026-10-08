package io.github.crossben.accordsync.server;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.postgresql.ds.PGSimpleDataSource;

/** A {@link javax.sql.DataSource} from a database URL, for the CLI and tests (serve with a pool such as HikariCP). */
public final class Databases {
    private Databases() {}

    /**
     * An unpooled data source.
     *
     * @param url {@code jdbc:postgresql://...} or {@code postgres[ql]://user:password@host:port/db}
     * @return the data source
     */
    public static PGSimpleDataSource fromUrl(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbcUrl(url));
        String userInfo = url.startsWith("jdbc:") ? null : URI.create(url).getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            ds.setUser(dec(colon < 0 ? userInfo : userInfo.substring(0, colon)));
            if (colon >= 0) ds.setPassword(dec(userInfo.substring(colon + 1)));
        }
        return ds;
    }

    /**
     * The JDBC URL of a database URL (credentials left out).
     *
     * @param url the URL
     * @return {@code jdbc:postgresql://host:port/db?...}
     */
    public static String jdbcUrl(String url) {
        if (url.startsWith("jdbc:")) return url;
        URI u = URI.create(url);
        if (!"postgres".equals(u.getScheme()) && !"postgresql".equals(u.getScheme())) {
            throw new IllegalArgumentException("not a PostgreSQL URL: " + u.getScheme());
        }
        String hostPort = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        return "jdbc:postgresql://" + hostPort + (u.getRawPath() == null ? "/" : u.getRawPath())
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
