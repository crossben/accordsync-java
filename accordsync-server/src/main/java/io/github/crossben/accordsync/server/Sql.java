package io.github.crossben.accordsync.server;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Small JDBC helpers: positional parameters, text[] arrays, rows as Object[]. */
final class Sql {
    private Sql() {}

    /** A text[] parameter. */
    record TextArray(Collection<String> items) {}

    /** A typed SQL null. */
    record Null(int type) {}

    static TextArray arr(Collection<String> items) {
        return new TextArray(items);
    }

    static void bind(Connection c, PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int k = i + 1;
            if (p instanceof TextArray a) {
                Array array = c.createArrayOf("text", a.items().toArray(new String[0]));
                ps.setArray(k, array);
            } else if (p instanceof Null n) {
                ps.setNull(k, n.type());
            } else if (p == null) {
                ps.setNull(k, Types.VARCHAR);
            } else if (p instanceof String s) {
                ps.setString(k, s);
            } else if (p instanceof Long l) {
                ps.setLong(k, l);
            } else if (p instanceof Integer n) {
                ps.setInt(k, n);
            } else if (p instanceof Double d) {
                ps.setDouble(k, d);
            } else {
                ps.setObject(k, p);
            }
        }
    }

    static List<Object[]> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                List<Object[]> rows = new ArrayList<>();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        Object v = rs.getObject(i + 1);
                        if (v instanceof Array a) v = a.getArray();
                        else if (v != null && rs.getMetaData().getColumnTypeName(i + 1).equals("jsonb")) v = rs.getString(i + 1);
                        row[i] = v;
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    static Object[] one(Connection c, String sql, Object... params) throws SQLException {
        List<Object[]> rows = query(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, params);
            return ps.executeUpdate();
        }
    }

    static String[] strings(Object v) {
        return v == null ? null : (String[]) v;
    }

    static long num(Object v) {
        return ((Number) v).longValue();
    }
}
