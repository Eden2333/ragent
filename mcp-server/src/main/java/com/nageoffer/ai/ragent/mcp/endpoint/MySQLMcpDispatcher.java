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

package com.nageoffer.ai.ragent.mcp.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpPrincipal;
import com.nageoffer.ai.ragent.mcp.mysql.tool.MySQLMcpTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL MCP JSON-RPC 方法分发器，仅分发 {@link MySQLMcpTool} 工具
 */
@Slf4j
@Component
public class MySQLMcpDispatcher {

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private final Map<String, MySQLMcpTool> toolMap = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;

    public MySQLMcpDispatcher(List<MySQLMcpTool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (MySQLMcpTool tool : tools) {
            toolMap.put(tool.getToolId(), tool);
        }
    }

    public static boolean isSupportedProtocolVersion(String version) {
        return SUPPORTED_PROTOCOL_VERSIONS.contains(version);
    }

    /**
     * 分发单条 JSON-RPC 消息
     *
     * @return 响应；通知或客户端发来的响应返回 null
     */
    public Map<String, Object> dispatch(JsonNode message, MySQLMcpPrincipal principal) {
        if (message == null || !message.isObject()) {
            return error(null, -32600, "Invalid Request");
        }
        JsonNode methodNode = message.get("method");
        if (methodNode == null) {
            if (message.has("result") || message.has("error")) {
                return null;
            }
            return error(null, -32600, "Invalid Request");
        }
        JsonNode idNode = message.get("id");
        Object id = null;
        if (idNode != null && (idNode.isTextual() || idNode.isNumber())) {
            id = idNode.isTextual() ? idNode.asText() : idNode.numberValue();
        }
        JsonNode jsonrpc = message.get("jsonrpc");
        if (jsonrpc == null || !"2.0".equals(jsonrpc.asText()) || !methodNode.isTextual()) {
            return error(id, -32600, "Invalid Request");
        }
        String method = methodNode.asText();
        if (idNode == null) {
            log.debug("MySQL MCP 收到通知: {}", method);
            return null;
        }
        if (id == null) {
            return error(null, -32600, "Invalid Request");
        }

        JsonNode paramsNode = message.get("params");
        Map<String, Object> params;
        if (paramsNode == null || paramsNode.isNull()) {
            params = new LinkedHashMap<>();
        } else if (!paramsNode.isObject()) {
            return error(id, -32602, "Invalid params");
        } else {
            params = toMap(paramsNode);
        }

        return switch (method) {
            case "initialize" -> success(id, initialize(params));
            case "ping" -> success(id, new LinkedHashMap<>());
            case "tools/list" -> success(id, toolsList());
            case "tools/call" -> toolsCall(id, params, principal);
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        String version = requested instanceof String str && isSupportedProtocolVersion(str) ? str : DEFAULT_PROTOCOL_VERSION;

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", false);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", tools);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ragent-mysql-mcp");
        serverInfo.put("version", "0.0.1");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", "只读 MySQL 工具：先调用 mysql_list_datasources 获取数据源，再查询表结构或执行 DQL");
        return result;
    }

    private Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (MySQLMcpTool tool : toolMap.values()) {
            MCPToolDefinition def = tool.getToolDefinition();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.getToolId());
            if (def.getDescription() != null) {
                item.put("description", def.getDescription());
            }
            item.put("inputSchema", toSchema(def));
            tools.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    private Map<String, Object> toSchema(MCPToolDefinition def) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (def.getParameters() != null) {
            def.getParameters().forEach((name, p) -> {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", p.getType());
                if (p.getDescription() != null) {
                    prop.put("description", p.getDescription());
                }
                if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                    prop.put("enum", p.getEnumValues());
                }
                properties.put(name, prop);
                if (p.isRequired()) {
                    required.add(name);
                }
            });
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private Map<String, Object> toolsCall(Object id, Map<String, Object> params, MySQLMcpPrincipal principal) {
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            return error(id, -32602, "Invalid params: missing tool name");
        }
        MySQLMcpTool tool = toolMap.get(name);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        Object argsObj = params.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argsObj instanceof Map<?, ?> map) {
            map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
        } else if (argsObj != null) {
            return error(id, -32602, "Invalid params: arguments must be an object");
        }
        MCPToolRequest request = MCPToolRequest.builder().toolId(name).parameters(arguments).build();

        MCPToolResponse response;
        try {
            response = tool.execute(request, principal);
        } catch (Exception e) {
            log.warn("MySQL MCP 工具[{}]执行异常: {}", name, e.getClass().getName());
            response = MCPToolResponse.error(name, "EXECUTION_ERROR", "工具执行异常，请联系管理员");
        }
        String text = response.isSuccess() ? response.getTextResult() : response.getErrorMessage();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("isError", !response.isSuccess());
        return success(id, result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, LinkedHashMap.class);
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    public Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", err);
        return response;
    }
}
