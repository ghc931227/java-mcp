package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.mcp.handler.McpToolHandler;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.*;

@ApplicationScoped
public class GetTableDdlHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(GetTableDdlHandler.class);

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Override
    public String getToolName() {
        return "jdbc-get-table-ddl";
    }

    @Override
    public String getDescription() {
        return "Generate CREATE TABLE DDL (columns, comments, primary key, indexes) for a table. " +
               "Oracle uses the data dictionary; other databases fall back to JDBC DatabaseMetaData.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID"));
        properties.put("tableName", Map.of("type", "string", "description", "Table name (Oracle: uppercase, e.g. ETL_TB_YTGL_YJSL_FEEDBACK)"));
        properties.put("owner", Map.of("type", "string", "description", "Schema/owner (optional, defaults to current schema)"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "Metadata query timeout in seconds (optional, default: 180, max: 1800)"));
        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId", "tableName"});
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String tableName = ((String) params.get("tableName")).trim();
        String owner = params.get("owner") == null ? null : ((String) params.get("owner")).trim();
        int timeoutSeconds = timeoutConfig.resolve(params);

        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName is required");
        }

        try (Connection conn = poolManager.getConnection(connectionId)) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            boolean isOracle = dbProduct != null && dbProduct.toLowerCase().contains("oracle");

            String effectiveOwner = owner;
            if (effectiveOwner == null || effectiveOwner.isEmpty()) {
                effectiveOwner = resolveCurrentSchema(conn, isOracle, timeoutSeconds);
            }

            String upperTable = isOracle ? tableName.toUpperCase() : tableName;
            String ddl = isOracle
                    ? buildOracleDdl(conn, effectiveOwner, upperTable, timeoutSeconds)
                    : buildGenericDdl(conn, upperTable);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("database", dbProduct);
            result.put("owner", effectiveOwner);
            result.put("tableName", upperTable);
            result.put("ddl", ddl);
            return result;
        }
    }

    private String resolveCurrentSchema(Connection conn, boolean isOracle, int timeoutSeconds) throws SQLException {
        if (isOracle) {
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(timeoutSeconds);
                try (ResultSet rs = st.executeQuery("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) {
                    if (rs.next()) {
                        String s = rs.getString(1);
                        if (s != null && !s.isEmpty()) return s;
                    }
                }
            } catch (SQLException e) {
                LOG.debugf("Cannot resolve current schema, fallback: %s", e.getMessage());
            }
        }
        String s = conn.getSchema();
        return (s == null || s.isEmpty()) ? "PUBLIC" : s;
    }

    // ===================== Oracle data dictionary =====================

    private String buildOracleDdl(Connection conn, String owner, String table, int timeoutSeconds) throws SQLException {
        StringBuilder out = new StringBuilder();
        out.append("-- ============================================================\n");
        out.append("-- TABLE: ").append(owner).append(".").append(table).append("\n");
        out.append("-- ============================================================\n");

        List<Map<String, String>> cols = oracleColumns(conn, owner, table, timeoutSeconds);
        if (cols.isEmpty()) {
            throw new IllegalArgumentException("Table not found or has no columns: " + owner + "." + table);
        }

        List<String> pk = oraclePrimaryKey(conn, owner, table, timeoutSeconds);
        List<Map<String, String>> cons = oracleOtherConstraints(conn, owner, table, timeoutSeconds);
        List<Map<String, String>> indexes = oracleIndexes(conn, owner, table, timeoutSeconds);
        String tabComment = oracleTableComment(conn, owner, table, timeoutSeconds);
        Map<String, String> colComments = oracleColumnComments(conn, owner, table, timeoutSeconds);

        out.append("DROP TABLE ").append(table).append(" CASCADE CONSTRAINTS;\n\n");
        out.append("CREATE TABLE ").append(table).append(" (\n");
        for (int i = 0; i < cols.size(); i++) {
            Map<String, String> c = cols.get(i);
            out.append("    ").append(c.get("name")).append(" ").append(c.get("type"));
            if (c.get("notnull") != null) out.append(" NOT NULL");
            if (c.get("default") != null) out.append(" DEFAULT ").append(c.get("default"));
            if (i < cols.size() - 1) out.append(",");
            out.append("\n");
        }
        if (!pk.isEmpty()) {
            out.append("    , CONSTRAINT PK_").append(table).append(" PRIMARY KEY (")
               .append(String.join(", ", pk)).append(")\n");
        }
        out.append(");\n\n");

        if (tabComment != null && !tabComment.isEmpty()) {
            out.append("COMMENT ON TABLE ").append(table).append(" IS '")
               .append(esc(tabComment)).append("';\n");
        }
        for (Map<String, String> c : cols) {
            String cm = colComments.get(c.get("name"));
            if (cm != null && !cm.isEmpty()) {
                out.append("COMMENT ON COLUMN ").append(table).append(".").append(c.get("name"))
                   .append(" IS '").append(esc(cm)).append("';\n");
            }
        }
        if (!colComments.isEmpty()) out.append("\n");

        for (Map<String, String> cn : cons) {
            String type = cn.get("type");
            if ("U".equals(type)) {
                out.append("ALTER TABLE ").append(table).append(" ADD CONSTRAINT ")
                   .append(cn.get("name")).append(" UNIQUE (").append(cn.get("cols")).append(");\n");
            } else if ("C".equals(type) && cn.get("cond") != null) {
                // skip auto-generated NOT NULL check constraints (columns already declare NOT NULL)
                if (isNotNullCheck(cn.get("cond"))) continue;
                out.append("ALTER TABLE ").append(table).append(" ADD CONSTRAINT ")
                   .append(cn.get("name")).append(" CHECK (").append(cn.get("cond")).append(");\n");
            }
        }
        if (!cons.isEmpty()) out.append("\n");

        for (Map<String, String> idx : indexes) {
            out.append("CREATE ");
            if ("UNIQUE".equals(idx.get("unique"))) out.append("UNIQUE ");
            out.append("INDEX ").append(idx.get("name")).append(" ON ").append(table)
               .append(" (").append(idx.get("cols")).append(");\n");
        }
        if (!indexes.isEmpty()) out.append("\n");

        return out.toString();
    }

    private List<Map<String, String>> oracleColumns(Connection conn, String owner, String table, int timeoutSeconds) throws SQLException {
        List<Map<String, String>> cols = new ArrayList<>();
        String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable, data_default " +
                "FROM all_tab_columns WHERE owner=? AND table_name=? ORDER BY column_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> c = new LinkedHashMap<>();
                    String name = rs.getString("COLUMN_NAME");
                    String dtype = rs.getString("DATA_TYPE");
                    int len = rs.getInt("DATA_LENGTH");
                    Integer prec = rs.getInt("DATA_PRECISION");
                    if (rs.wasNull()) prec = null;
                    Integer scale = rs.getInt("DATA_SCALE");
                    if (rs.wasNull()) scale = null;
                    String nullable = rs.getString("NULLABLE");
                    String def = rs.getString("DATA_DEFAULT");

                    c.put("name", name);
                    c.put("type", oracleType(dtype, len, prec, scale));
                    if ("N".equals(nullable)) c.put("notnull", "Y");
                    if (def != null && !def.isEmpty()) c.put("default", def.trim());
                    cols.add(c);
                }
            }
        }
        return cols;
    }

    private String oracleType(String dtype, int len, Integer prec, Integer scale) {
        String d = dtype == null ? "" : dtype.trim().toUpperCase();
        if (d.startsWith("TIMESTAMP") || d.startsWith("DATE") || d.startsWith("CLOB") || d.startsWith("BLOB")
                || d.startsWith("RAW") || d.startsWith("LONG") || d.startsWith("NCLOB")
                || d.startsWith("XMLTYPE")) {
            return d;
        }
        if (d.startsWith("VARCHAR2") || d.startsWith("VARCHAR") || d.startsWith("CHAR") || d.startsWith("NVARCHAR2")
                || d.startsWith("NCHAR")) {
            return d + "(" + len + ")";
        }
        if (d.startsWith("NUMBER")) {
            if (prec != null && scale != null && scale == 0) return "NUMBER(" + prec + ")";
            if (prec != null && scale != null) return "NUMBER(" + prec + "," + scale + ")";
            return "NUMBER";
        }
        if (d.startsWith("FLOAT")) return "FLOAT(" + len + ")";
        return d;
    }

    private List<String> oraclePrimaryKey(Connection conn, String owner, String table, int timeoutSeconds) throws SQLException {
        List<String> pk = new ArrayList<>();
        String sql = "SELECT cols.column_name FROM all_constraints c, all_cons_columns cols " +
                "WHERE c.owner=? AND c.table_name=? AND c.constraint_type='P' " +
                "AND cols.owner=c.owner AND cols.constraint_name=c.constraint_name ORDER BY cols.position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        }
        return pk;
    }

    private List<Map<String, String>> oracleOtherConstraints(Connection conn, String owner, String table, int timeoutSeconds) throws SQLException {
        List<Map<String, String>> cons = new ArrayList<>();
        // search_condition is LONG, cannot be used in GROUP BY / LISTAGG -- fetch it separately per constraint
        String sql = "SELECT c.constraint_name, c.constraint_type, " +
                "LISTAGG(cols.column_name, ', ') WITHIN GROUP (ORDER BY cols.position) AS cols " +
                "FROM all_constraints c LEFT JOIN all_cons_columns cols " +
                "ON cols.owner=c.owner AND cols.constraint_name=c.constraint_name " +
                "WHERE c.owner=? AND c.table_name=? AND c.constraint_type IN ('U','C') " +
                "GROUP BY c.constraint_name, c.constraint_type";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("CONSTRAINT_NAME"));
                    m.put("type", rs.getString("CONSTRAINT_TYPE"));
                    m.put("cols", rs.getString("COLS"));
                    m.put("cond", fetchSearchCondition(conn, owner, rs.getString("CONSTRAINT_NAME"), timeoutSeconds));
                    cons.add(m);
                }
            }
        }
        return cons;
    }

    private String fetchSearchCondition(Connection conn, String owner, String constraintName, int timeoutSeconds) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT search_condition FROM all_constraints WHERE owner=? AND constraint_name=?")) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.debugf("search_condition fetch failed: %s", e.getMessage());
        }
        return null;
    }

    private List<Map<String, String>> oracleIndexes(Connection conn, String owner, String table, int timeoutSeconds) throws SQLException {
        List<Map<String, String>> idxs = new ArrayList<>();
        String sql = "SELECT i.index_name, i.uniqueness, " +
                "LISTAGG(c.column_name, ', ') WITHIN GROUP (ORDER BY c.column_position) AS cols " +
                "FROM all_indexes i, all_ind_columns c " +
                "WHERE i.owner=? AND i.table_name=? AND i.index_name NOT LIKE 'PK%' " +
                "AND c.index_owner=i.owner AND c.index_name=i.index_name " +
                "GROUP BY i.index_name, i.uniqueness";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("INDEX_NAME"));
                    m.put("unique", rs.getString("UNIQUENESS"));
                    m.put("cols", rs.getString("COLS"));
                    idxs.add(m);
                }
            }
        }
        return idxs;
    }

    private String oracleTableComment(Connection conn, String owner, String table, int timeoutSeconds) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT comments FROM all_tab_comments WHERE owner=? AND table_name=?")) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.debugf("table comment failed: %s", e.getMessage());
        }
        return null;
    }

    private Map<String, String> oracleColumnComments(Connection conn, String owner, String table, int timeoutSeconds) {
        Map<String, String> m = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, comments FROM all_col_comments WHERE owner=? AND table_name=? AND comments IS NOT NULL")) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            LOG.debugf("column comments failed: %s", e.getMessage());
        }
        return m;
    }

    // ===================== generic JDBC fallback =====================

    private String buildGenericDdl(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        StringBuilder out = new StringBuilder();
        out.append("-- TABLE: ").append(table).append("\n");
        out.append("CREATE TABLE ").append(table).append(" (\n");

        List<Map<String, Object>> cols = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", rs.getString("COLUMN_NAME"));
                c.put("type", rs.getString("TYPE_NAME"));
                c.put("size", rs.getInt("COLUMN_SIZE"));
                c.put("nullable", rs.getInt("NULLABLE"));
                c.put("def", rs.getString("COLUMN_DEF"));
                cols.add(c);
            }
        }
        if (cols.isEmpty()) {
            throw new IllegalArgumentException("Table not found: " + table);
        }
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> c = cols.get(i);
            out.append("    ").append(c.get("name")).append(" ").append(c.get("type"));
            out.append("(").append(c.get("size")).append(")");
            if (Integer.valueOf(0).equals(c.get("nullable"))) out.append(" NOT NULL");
            if (c.get("def") != null) out.append(" DEFAULT '").append(esc(String.valueOf(c.get("def")))).append("'");
            if (i < cols.size() - 1) out.append(",");
            out.append("\n");
        }
        out.append(");\n");
        return out.toString();
    }

    private String esc(String s) {
        return s.replace("'", "''");
    }

    private boolean isNotNullCheck(String cond) {
        if (cond == null) return false;
        String t = cond.trim().toUpperCase();
        return t.matches("\"[A-Z0-9_$#]+\"\\s+IS NOT NULL");
    }
}