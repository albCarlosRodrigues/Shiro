package dev.squaddesktop.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Leitura de colunas com os tipos que o SQLite realmente guarda (texto, inteiro, real). */
public final class Row {

    private final ResultSet rs;

    Row(ResultSet rs) {
        this.rs = rs;
    }

    /** Valor como o SQLite guarda: String, Long, Double ou null (BLOB vira null; o esquema não usa). */
    public Object object(String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        return value instanceof byte[] ? null : value;
    }

    public String str(String column) throws SQLException {
        return rs.getString(column);
    }

    public String strOrEmpty(String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    public int integer(String column) throws SQLException {
        return rs.getInt(column);
    }

    public Integer integerOrNull(String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public long lng(String column) throws SQLException {
        return rs.getLong(column);
    }

    public Long lngOrNull(String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public Double dblOrNull(String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public boolean bool(String column) throws SQLException {
        return rs.getInt(column) != 0;
    }
}
