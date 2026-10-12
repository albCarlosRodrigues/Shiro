package dev.squaddesktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Operações SQL sobre a conexão da unidade de trabalho corrente. Obtido por {@link Database#read} ou {@link Database#write}. */
public final class Tx {

    private final Connection connection;
    private final boolean writable;

    Tx(Connection connection, boolean writable) {
        this.connection = connection;
        this.writable = writable;
    }

    public boolean writable() {
        return writable;
    }

    public int update(String sql, Object... params) {
        if (!writable) {
            throw new StoreException("Escrita fora de Database.write: " + firstWords(sql));
        }
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Falha ao executar: " + firstWords(sql), e);
        }
    }

    /** Executa um INSERT numa tabela com chave INTEGER PRIMARY KEY e devolve o rowid gerado. */
    public long insert(String sql, Object... params) {
        update(sql, params);
        return scalarLong("SELECT last_insert_rowid()");
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            Row row = new Row(rs);
            while (rs.next()) {
                out.add(mapper.map(row));
            }
            return out;
        } catch (SQLException e) {
            throw new StoreException("Falha ao consultar: " + firstWords(sql), e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public long scalarLong(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new StoreException("Falha ao consultar: " + firstWords(sql), e);
        }
    }

    public String scalarString(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new StoreException("Falha ao consultar: " + firstWords(sql), e);
        }
    }

    /** Executa um comando sem parâmetros (DDL, PRAGMA). */
    public void execute(String sql) {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new StoreException("Falha ao executar: " + firstWords(sql), e);
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                bind(ps, i + 1, params[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        switch (value) {
            case null -> ps.setNull(index, Types.NULL);
            case String s -> ps.setString(index, s);
            case Integer i -> ps.setInt(index, i);
            case Long l -> ps.setLong(index, l);
            case Boolean b -> ps.setInt(index, b ? 1 : 0);
            case Double d -> ps.setDouble(index, d);
            case Enum<?> e -> ps.setString(index, e.name());
            default -> throw new StoreException("Tipo de parâmetro não suportado: " + value.getClass().getName());
        }
    }

    private static String firstWords(String sql) {
        String flat = sql.strip().replaceAll("\\s+", " ");
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }
}
