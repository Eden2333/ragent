/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.mysql.tool;

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mysql_get_table_schemas：查询 information_schema 获取表结构（列、主键、索引）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLTableSchemaTool implements MySQLMcpTool {

    private static final String TOOL_ID = "mysql_get_table_schemas";

    private static final String TABLE_SQL = "SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COMMENT FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = ?";

    private static final String COLUMN_SQL = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY, EXTRA, COLUMN_COMMENT "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?";

    private static final String INDEX_SQL = "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE "
            + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ?";

    private final MySQLToolSupport support;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();
        parameters.put("datasource", MCPToolDefinition.ParameterDef.builder()
                .description("数据源名称，可通过 mysql_list_datasources 获取")
                .type("string")
                .required(true)
                .build());
        parameters.put("database", MCPToolDefinition.ParameterDef.builder()
                .description("库名（schema），不填则使用数据源连接的默认库")
                .type("string")
                .required(false)
                .build());
        parameters.put("tableName", MCPToolDefinition.ParameterDef.builder()
                .description("表名（精确匹配），不填则返回库中全部表")
                .type("string")
                .required(false)
                .build());
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("只读：获取 MySQL 数据库的表结构，包括表名、表注释、字段（名称、类型、是否可空、默认值、注释）、主键和索引")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request, MySQLMcpPrincipal principal) {
        try {
            String datasource = support.requireDatasource(request, principal);
            String database = support.optString(request, "database");
            String tableName = support.optString(request, "tableName");
            int timeout = support.queryTimeoutSeconds();
            int maxTables = Math.max(1, support.getProperties().getMaxSchemaTables());
            Map<String, Object> result = support.getDataSourceManager().executeReadOnly(datasource, null,
                    conn -> loadSchema(conn, datasource, database, tableName, timeout, maxTables), timeout);
            return MCPToolResponse.success(TOOL_ID, support.toJson(result));
        } catch (Exception e) {
            return support.error(TOOL_ID, e);
        }
    }

    private Map<String, Object> loadSchema(Connection conn, String datasource, String database, String tableName,
                                           int timeout, int maxTables) throws SQLException {
        String schema = database != null ? database : currentDatabase(conn, timeout);
        if (schema == null) {
            throw new MySQLMcpToolException("当前连接未指定默认库，请传入 database 参数");
        }

        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        boolean truncated = false;
        try (PreparedStatement ps = prepare(conn, TABLE_SQL, " ORDER BY TABLE_NAME", schema, tableName, timeout)) {
            ps.setMaxRows(maxTables + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (tables.size() >= maxTables) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> table = new LinkedHashMap<>();
                    String name = rs.getString("TABLE_NAME");
                    table.put("tableName", name);
                    table.put("tableType", rs.getString("TABLE_TYPE"));
                    table.put("engine", rs.getString("ENGINE"));
                    table.put("comment", rs.getString("TABLE_COMMENT"));
                    table.put("columns", new ArrayList<Map<String, Object>>());
                    table.put("primaryKey", new ArrayList<String>());
                    table.put("indexes", new ArrayList<Map<String, Object>>());
                    tables.put(name, table);
                }
            }
        }

        if (!tables.isEmpty()) {
            loadColumns(conn, schema, tableName, timeout, tables);
            loadIndexes(conn, schema, tableName, timeout, tables);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasource", datasource);
        result.put("database", schema);
        result.put("tableCount", tables.size());
        result.put("truncated", truncated);
        result.put("tables", new ArrayList<>(tables.values()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void loadColumns(Connection conn, String schema, String tableName, int timeout,
                             Map<String, Map<String, Object>> tables) throws SQLException {
        try (PreparedStatement ps = prepare(conn, COLUMN_SQL, " ORDER BY TABLE_NAME, ORDINAL_POSITION", schema, tableName, timeout);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> table = tables.get(rs.getString("TABLE_NAME"));
                if (table == null) {
                    continue;
                }
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", rs.getString("COLUMN_NAME"));
                column.put("type", rs.getString("COLUMN_TYPE"));
                column.put("nullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                column.put("default", rs.getString("COLUMN_DEFAULT"));
                column.put("key", rs.getString("COLUMN_KEY"));
                column.put("extra", rs.getString("EXTRA"));
                column.put("comment", rs.getString("COLUMN_COMMENT"));
                ((List<Map<String, Object>>) table.get("columns")).add(column);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadIndexes(Connection conn, String schema, String tableName, int timeout,
                             Map<String, Map<String, Object>> tables) throws SQLException {
        try (PreparedStatement ps = prepare(conn, INDEX_SQL, " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", schema, tableName, timeout);
             ResultSet rs = ps.executeQuery()) {
            Map<String, Map<String, Object>> indexByKey = new LinkedHashMap<>();
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                Map<String, Object> tableInfo = tables.get(table);
                if (tableInfo == null) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                Map<String, Object> index = indexByKey.computeIfAbsent(table + "\u0000" + indexName, k -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("name", indexName);
                    created.put("unique", false);
                    created.put("type", null);
                    created.put("columns", new ArrayList<String>());
                    ((List<Map<String, Object>>) tableInfo.get("indexes")).add(created);
                    return created;
                });
                index.put("unique", rs.getInt("NON_UNIQUE") == 0);
                index.put("type", rs.getString("INDEX_TYPE"));
                // 函数索引的 COLUMN_NAME 为 null
                ((List<String>) index.get("columns")).add(column);
                if ("PRIMARY".equals(indexName) && column != null) {
                    ((List<String>) tableInfo.get("primaryKey")).add(column);
                }
            }
        }
    }

    private PreparedStatement prepare(Connection conn, String baseSql, String orderBy, String schema, String tableName,
                                      int timeout) throws SQLException {
        String sql = baseSql + (tableName != null ? " AND TABLE_NAME = ?" : "") + orderBy;
        PreparedStatement ps = conn.prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeout);
            ps.setString(1, schema);
            if (tableName != null) {
                ps.setString(2, tableName);
            }
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }

    private String currentDatabase(Connection conn, int timeout) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeout);
            try (ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
