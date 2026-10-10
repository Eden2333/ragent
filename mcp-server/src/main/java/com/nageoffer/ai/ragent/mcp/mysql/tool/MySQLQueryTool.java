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
import com.nageoffer.ai.ragent.mcp.mysql.sql.SqlReadOnlyValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mysql_execute_query：执行只读查询（DQL），禁止任何变更类 SQL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLQueryTool implements MySQLMcpTool {

    private static final String TOOL_ID = "mysql_execute_query";

    private static final int LOG_SQL_MAX_LENGTH = 500;

    private final MySQLToolSupport support;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();
        parameters.put("datasource", MCPToolDefinition.ParameterDef.builder()
                .description("数据源名称，可通过 mysql_list_datasources 获取")
                .type("string")
                .required(true)
                .build());
        parameters.put("sql", MCPToolDefinition.ParameterDef.builder()
                .description("单条只读 SQL，仅支持 SELECT / WITH ... SELECT / SHOW / DESC / DESCRIBE / EXPLAIN")
                .type("string")
                .required(true)
                .build());
        parameters.put("database", MCPToolDefinition.ParameterDef.builder()
                .description("执行前切换到的库名，不填则使用数据源连接的默认库")
                .type("string")
                .required(false)
                .build());
        parameters.put("maxRows", MCPToolDefinition.ParameterDef.builder()
                .description("最多返回行数，默认 " + support.getProperties().getDefaultMaxRows()
                        + "，上限 " + support.getProperties().getMaxRowsLimit())
                .type("integer")
                .required(false)
                .build());
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("只读：在 MySQL 上执行单条查询类 SQL（DQL），禁止 INSERT/UPDATE/DELETE/DDL/SET/CALL 等变更语句，"
                        + "返回列名、行数据（二维数组）以及是否被截断")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request, MySQLMcpPrincipal principal) {
        try {
            String datasource = support.requireDatasource(request, principal);
            String sql = request.getStringParameter("sql");
            if (sql == null || sql.isBlank()) {
                throw new MySQLMcpToolException("缺少参数 sql");
            }
            // 取连接之前完成静态校验
            SqlReadOnlyValidator.validate(sql);
            String database = support.optString(request, "database");
            int maxRows = support.resolveMaxRows(request);
            int timeout = support.queryTimeoutSeconds();

            log.info("MySQL MCP 查询, token={}, datasource={}, database={}, sql={}",
                    principal.name(), datasource, database, abbreviate(sql));
            long start = System.currentTimeMillis();
            Map<String, Object> result = support.getDataSourceManager().executeReadOnly(datasource, database, conn -> {
                try (Statement st = conn.createStatement()) {
                    st.setEscapeProcessing(false);
                    st.setQueryTimeout(timeout);
                    st.setMaxRows(maxRows + 1);
                    if (!st.execute(sql)) {
                        throw new MySQLMcpToolException("仅支持返回结果集的查询语句");
                    }
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        List<String> columns = new ArrayList<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }
                        List<List<Object>> rows = new ArrayList<>();
                        boolean truncated = false;
                        while (rs.next()) {
                            if (rows.size() >= maxRows) {
                                truncated = true;
                                break;
                            }
                            List<Object> row = new ArrayList<>(columnCount);
                            for (int i = 1; i <= columnCount; i++) {
                                row.add(support.toJsonValue(rs.getObject(i)));
                            }
                            rows.add(row);
                        }
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("datasource", datasource);
                        data.put("database", database);
                        data.put("columns", columns);
                        data.put("rows", rows);
                        data.put("rowCount", rows.size());
                        data.put("truncated", truncated);
                        data.put("maxRows", maxRows);
                        return data;
                    }
                }
            }, timeout);
            result.put("costMs", System.currentTimeMillis() - start);
            return MCPToolResponse.success(TOOL_ID, support.toJson(result));
        } catch (Exception e) {
            return support.error(TOOL_ID, e);
        }
    }

    private String abbreviate(String sql) {
        return sql.length() > LOG_SQL_MAX_LENGTH ? sql.substring(0, LOG_SQL_MAX_LENGTH) + "..." : sql;
    }
}
