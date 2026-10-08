package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.server.Access;
import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.Auth;
import io.github.crossben.accordsync.server.Limits;
import io.github.crossben.accordsync.server.ServerDefinition;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A definition and a data source that is never reachable. */
final class Stubs {
    private Stubs() {}

    static ServerDefinition definition() {
        return AccordServer.define(d -> d
                .schema(Schema.define(Map.of("note", Map.of("title", "lww"))))
                .scope("note", r -> List.of("all"))
                .access(c -> Access.readWrite("all"))
                .auth(Auth.hs256("0123456789abcdef0123456789abcdef"))
                .cors(List.of("https://app.example"))
                .limits(Limits.DEFAULT.withMaxBodyBytes(1024)));
    }

    /** Every getConnection() fails, like a database that is down. */
    static final class DownDataSource implements DataSource {
        int attempts;

        @Override
        public Connection getConnection() throws SQLException {
            attempts++;
            throw new SQLException("database down");
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
