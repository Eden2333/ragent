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

package com.nageoffer.ai.ragent.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MCP JSON-RPC 协议处理器，所有 MCP Server 共用
 * <p>
 * 负责：JSON-RPC 2.0 消息校验与标准错误码、initialize 版本协商、ping、tools/list、tools/call；
 * tools/call 统一做舱壁限流、异常脱敏与调用日志。领域 Server 只需提供工具与配置。
 * <p>
 * 错误分层遵循 MCP 规范：协议错误（方法不存在、参数非法、工具不存在）返回 JSON-RPC error；
 * 工具执行错误放进 result 并标记 isError=true，让模型能读到原因并自行修正。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpProtocolHandler {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int UNAUTHORIZED = -32001;

    static final String GENERIC_TOOL_ERROR = "工具执行异常，请联系管理员";

    private final ObjectMapper objectMapper;

    /**
     * 处理单条 JSON-RPC 消息
     *
     * @return 响应；通知或客户端发来的响应返回 null（由传输层返回 202）
     */
    public Map<String, Object> handle(McpServerRuntime server, JsonNode message, McpPrincipal principal) {
        if (message == null || !message.isObject()) {
            return error(null, INVALID_REQUEST, "Invalid Request");
        }
        JsonNode methodNode = message.get("method");
        if (methodNode == null) {
            if (message.has("result") || message.has("error")) {
                return null;
            }
            return error(null, INVALID_REQUEST, "Invalid Request");
        }
        JsonNode idNode = message.get("id");
        Object id = null;
        if (idNode != null && (idNode.isTextual() || idNode.isNumber())) {
            id = idNode.isTextual() ? idNode.asText() : idNode.numberValue();
        }
        JsonNode jsonrpc = message.get("jsonrpc");
        if (jsonrpc == null || !"2.0".equals(jsonrpc.asText()) || !methodNode.isTextual()) {
            return error(id, INVALID_REQUEST, "Invalid Request");
        }
        String method = methodNode.asText();
        if (idNode == null) {
            log.debug("MCP Server[{}] 收到通知: {}", server.name(), method);
            return null;
        }
        if (id == null) {
            return error(null, INVALID_REQUEST, "Invalid Request: id must be a string or number");
        }

        JsonNode paramsNode = message.get("params");
        Map<String, Object> params;
        if (paramsNode == null || paramsNode.isNull()) {
            params = new LinkedHashMap<>();
        } else if (!paramsNode.isObject()) {
            return error(id, INVALID_PARAMS, "Invalid params: params must be an object");
        } else {
            params = toMap(paramsNode);
        }

        return switch (method) {
            case "initialize" -> success(id, initialize(server, params));
            case "ping" -> success(id, new LinkedHashMap<>());
            case "tools/list" -> success(id, toolsList(server));
            case "tools/call" -> toolsCall(server, id, params, principal);
            default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private Map<String, Object> initialize(McpServerRuntime server, Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        String version = McpProtocolVersions.negotiate(requested instanceof String str ? str : null);

        Map<String, Object> tools = new LinkedHashMap<>();
        // 工具集在启动时固定，不会变化
        tools.put("listChanged", false);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", tools);

        McpServerDefinition definition = server.definition();
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", definition.serverInfoName());
        serverInfo.put("version", definition.version());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        String instructions = definition.instructions();
        if (instructions != null && !instructions.isBlank()) {
            result.put("instructions", instructions);
        }
        return result;
    }

    private Map<String, Object> toolsList(McpServerRuntime server) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpTool tool : server.tools()) {
            MCPToolDefinition def = tool.getToolDefinition();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.getToolId());
            if (def.getDescription() != null) {
                item.put("description", def.getDescription());
            }
            item.put("inputSchema", toInputSchema(def));
            tools.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    /**
     * 工具参数定义转换为 JSON Schema：字段名用 enum/default/required，空值字段不输出；
     * 无参数工具按规范推荐写 additionalProperties=false
     */
    Map<String, Object> toInputSchema(MCPToolDefinition def) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (def.getParameters() != null) {
            def.getParameters().forEach((name, p) -> {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", p.getType() == null ? "string" : p.getType());
                if (p.getDescription() != null) {
                    prop.put("description", p.getDescription());
                }
                if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                    prop.put("enum", p.getEnumValues());
                }
                if (p.getDefaultValue() != null) {
                    prop.put("default", p.getDefaultValue());
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
        if (properties.isEmpty()) {
            schema.put("additionalProperties", false);
        }
        return schema;
    }

    private Map<String, Object> toolsCall(McpServerRuntime server, Object id, Map<String, Object> params, McpPrincipal principal) {
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            return error(id, INVALID_PARAMS, "Invalid params: missing tool name");
        }
        McpTool tool = server.tool(name);
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool: " + name);
        }
        Object argsObj = params.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argsObj instanceof Map<?, ?> map) {
            map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
        } else if (argsObj != null) {
            return error(id, INVALID_PARAMS, "Invalid params: arguments must be an object");
        }

        if (!server.tryAcquire()) {
            log.warn("MCP Server[{}] 并发调用已达上限 {}，拒绝工具[{}]调用, caller={}",
                    server.name(), server.maxConcurrentCalls(), name, principal.name());
            return success(id, toolResult(false, "服务繁忙：当前并发调用已达上限，请稍后重试"));
        }
        long start = System.currentTimeMillis();
        MCPToolResponse response;
        try {
            MCPToolRequest request = MCPToolRequest.builder().toolId(name).parameters(arguments).build();
            response = tool.execute(request, principal);
            if (response == null) {
                response = MCPToolResponse.error(name, "EXECUTION_ERROR", GENERIC_TOOL_ERROR);
            }
        } catch (McpToolException e) {
            response = MCPToolResponse.error(name, "INVALID_REQUEST", e.getMessage());
        } catch (Exception e) {
            // 详细异常只留在服务端日志，不返回给客户端
            log.warn("MCP Server[{}] 工具[{}]执行异常", server.name(), name, e);
            response = MCPToolResponse.error(name, "EXECUTION_ERROR", GENERIC_TOOL_ERROR);
        } finally {
            server.release();
        }
        log.info("MCP 工具调用完成, server={}, tool={}, caller={}, success={}, errorCode={}, costMs={}",
                server.name(), name, principal.name(), response.isSuccess(), response.getErrorCode(),
                System.currentTimeMillis() - start);

        String text = response.isSuccess()
                ? response.getTextResult()
                : firstNonBlank(response.getErrorMessage(), response.getTextResult());
        return success(id, toolResult(response.isSuccess(), text));
    }

    private Map<String, Object> toolResult(boolean success, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("isError", !success);
        return result;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, LinkedHashMap.class);
    }

    /**
     * JSON-RPC 成功响应：只含 result，不含 error 字段
     */
    public Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * JSON-RPC 错误响应：只含 error，不含 result 字段
     */
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
