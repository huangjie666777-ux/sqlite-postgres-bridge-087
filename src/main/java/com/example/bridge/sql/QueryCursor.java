package com.example.bridge.sql;

import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** A forward-only streaming view over one query result. */
public final class QueryCursor implements AutoCloseable {

    private final Statement statement;
    private final ResultSet resultSet;
    private final List<String> columnNames;
    private final int columnCount;

    QueryCursor(Statement statement, ResultSet resultSet) throws SQLException {
        this.statement = statement;
        this.resultSet = resultSet;
        ResultSetMetaData meta = resultSet.getMetaData();
        this.columnCount = meta.getColumnCount();
        this.columnNames = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(meta.getColumnLabel(i));
        }
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public boolean next() throws SQLException {
        return resultSet.next();
    }

    /**
     * Reads the current row. Every column is encoded as text: numbers use their natural
     * textual form, BLOBs become lowercase hex; null yields null (distinct from "").
     */
    public List<byte[]> currentRow() throws SQLException {
        List<byte[]> row = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            row.add(encodeColumn(i));
        }
        return row;
    }

    private byte[] encodeColumn(int index) throws SQLException {
        Object value = resultSet.getObject(index);
        if (resultSet.wasNull() || value == null) {
            return null;
        }
        String text;
        if (value instanceof byte[] bytes) {
            text = HexFormat.of().formatHex(bytes);
        } else if (value instanceof Blob blob) {
            long length = blob.length();
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("BLOB too large to encode as text");
            }
            text = HexFormat.of().formatHex(blob.getBytes(1, (int) length));
        } else if (value instanceof Boolean b) {
            text = b ? "1" : "0";
        } else {
            text = value.toString();
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public void cancel() throws SQLException {
        statement.cancel();
    }

    @Override
    public void close() {
        try {
            resultSet.close();
        } catch (SQLException ignored) {
            // best effort
        }
        try {
            statement.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
