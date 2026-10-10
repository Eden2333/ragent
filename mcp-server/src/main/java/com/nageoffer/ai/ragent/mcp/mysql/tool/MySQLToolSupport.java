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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpPrincipal;
import com.nageoffer.ai.ragent.mcp.mysql.config.MySQLMcpProperties;
import com.nageoffer.ai.ragent.mcp.mysql.datasource.MySQLDataSourceManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * MySQL MCP 工具公共方法：参数读取、数据源权限校验、JDBC 值转换、JSON 输出、错误格式化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLToolSupport {

    private static final int MAX_STRING_LENGTH = 10000;

    @Getter
    private final MySQLDataSourceManager dataSourceManager;

    @Getter
    private final MySQLMcpProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 读取 datasource 参数并校验当前 token 是否有权访问
     */
    public String requireDatasource(MCPToolRequest request, MySQLMcpPrincipal principal) {
        String ds = optString(request, "datasource");
        if (ds == null) {
            throw new MySQLMcpToolException("缺少参数 datasource");
        }
        if (principal == null || !principal.datasources().contains(ds) || !dataSourceManager.isAvailable(ds)) {
            throw new MySQLMcpToolException("数据源不存在或无访问权限: " + ds);
        }
        return ds;
    }

    public String optString(MCPToolRequest request, String key) {
        String value = request.getStringParameter(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    public int resolveMaxRows(MCPToolRequest request) {
        Object raw = request.getParameters() == null ? null : request.getParameters().get("maxRows");
        int maxRows;
        if (raw == null || (raw instanceof String str && str.isBlank())) {
            maxRows = properties.getDefaultMaxRows();
        } else if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            maxRows = (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, ((Number) raw).longValue()));
        } else {
            try {
                maxRows = new BigDecimal(raw.toString().trim()).intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new MySQLMcpToolException("maxRows 必须为整数");
            }
        }
        int limit = Math.max(1, properties.getMaxRowsLimit());
        return Math.max(1, Math.min(maxRows, limit));
    }

    public int queryTimeoutSeconds() {
        return Math.max(1, properties.getQueryTimeoutSeconds());
    }

    /**
     * JDBC 值转换为 JSON 友好的值
     */
    public Object toJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str.length() > MAX_STRING_LENGTH ? str.substring(0, MAX_STRING_LENGTH) + "...(truncated)" : str;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof Double || value instanceof Float || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        String text = value.toString();
        return text.length() > MAX_STRING_LENGTH ? text.substring(0, MAX_STRING_LENGTH) + "...(truncated)" : text;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new MySQLMcpToolException("结果序列化失败");
        }
    }

    /**
     * 将异常转换为安全的工具错误响应（不含堆栈、密码）
     */
    public MCPToolResponse error(String toolId, Exception e) {
        String message;
        String code;
        if (e instanceof MySQLMcpToolException) {
            code = "INVALID_REQUEST";
            message = e.getMessage();
        } else if (e instanceof SQLTimeoutException) {
            code = "QUERY_TIMEOUT";
            message = "查询超时（超过 " + queryTimeoutSeconds() + " 秒）";
        } else if (e instanceof SQLException sqlException) {
            code = "SQL_ERROR";
            message = "SQL 执行失败 [SQLState=" + sqlException.getSQLState() + ", ErrorCode=" + sqlException.getErrorCode() + "]: "
                    + dataSourceManager.sanitize(sqlException.getMessage());
        } else {
            code = "EXECUTION_ERROR";
            message = "工具执行异常，请联系管理员";
            log.warn("MySQL MCP 工具[{}]执行异常: {} {}", toolId, e.getClass().getName(), dataSourceManager.sanitize(e.getMessage()));
        }
        return MCPToolResponse.error(toolId, code, message);
    }
}
