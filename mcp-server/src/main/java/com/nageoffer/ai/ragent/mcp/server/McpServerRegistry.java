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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MCP Server 注册表
 * <p>
 * 启动时收集全部 {@link McpServerDefinition} 并做快速失败校验：路径格式与唯一性、Server 名称唯一、
 * 工具名格式与 Server 内唯一、并发上限合法。任何一项不满足都会阻止应用启动，避免带病对外暴露工具。
 */
@Slf4j
@Component
public class McpServerRegistry {

    /**
     * 默认 Server 为 /mcp，领域 Server 为 /mcp/{小写字母数字与中划线}
     */
    private static final Pattern PATH_PATTERN = Pattern.compile("^/mcp(/[a-z0-9]+(-[a-z0-9]+)*)?$");

    /**
     * MCP 规范建议的工具名字符集与长度
     */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");

    private final Map<String, McpServerRuntime> serversByPath;

    public McpServerRegistry(List<McpServerDefinition> definitions) {
        Map<String, McpServerRuntime> byPath = new LinkedHashMap<>();
        Map<String, String> pathByName = new HashMap<>();
        Map<String, List<String>> serversByToolName = new LinkedHashMap<>();

        for (McpServerDefinition definition : definitions == null ? List.<McpServerDefinition>of() : definitions) {
            String name = definition.name();
            String path = definition.path();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("MCP Server 名称不能为空: " + definition.getClass().getName());
            }
            if (path == null || !PATH_PATTERN.matcher(path).matches()) {
                throw new IllegalStateException("MCP Server[" + name + "] 路径非法: " + path
                        + "，必须为 /mcp 或 /mcp/{小写字母数字与中划线}");
            }
            if (pathByName.containsKey(name)) {
                throw new IllegalStateException("MCP Server 名称重复: " + name);
            }
            if (byPath.containsKey(path)) {
                throw new IllegalStateException("MCP Server 路径冲突: " + path + " 同时被 "
                        + byPath.get(path).name() + " 与 " + name + " 使用");
            }
            int maxConcurrent = definition.maxConcurrentCalls();
            if (maxConcurrent < 1) {
                throw new IllegalStateException("MCP Server[" + name + "] maxConcurrentCalls 必须 >= 1");
            }
            McpAuthenticator authenticator = definition.authenticator();
            if (authenticator == null) {
                throw new IllegalStateException("MCP Server[" + name + "] 未配置鉴权策略");
            }

            Map<String, McpTool> tools = new LinkedHashMap<>();
            for (McpTool tool : definition.tools() == null ? List.<McpTool>of() : definition.tools()) {
                String toolName = tool.getToolId();
                if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()) {
                    throw new IllegalStateException("MCP Server[" + name + "] 工具名非法: " + toolName
                            + "，只允许 1-128 位字母、数字、下划线、中划线与点");
                }
                if (tools.putIfAbsent(toolName, tool) != null) {
                    throw new IllegalStateException("MCP Server[" + name + "] 工具名重复: " + toolName);
                }
                serversByToolName.computeIfAbsent(toolName, k -> new ArrayList<>()).add(name);
            }

            pathByName.put(name, path);
            byPath.put(path, new McpServerRuntime(definition, tools, authenticator, maxConcurrent));
            log.info("MCP Server 已注册: name={}, path={}, auth={}, maxConcurrentCalls={}, tools={}",
                    name, path, authenticator.requiresCredentials() ? "required" : "none", maxConcurrent, tools.keySet());
        }

        // 不同 Server 之间同名不影响服务端，但客户端把多个 Server 的工具平铺到一个命名空间时会冲突
        serversByToolName.forEach((toolName, servers) -> {
            if (servers.size() > 1) {
                log.warn("工具名 {} 同时出现在多个 MCP Server {} 中，客户端聚合时可能冲突，建议加领域前缀", toolName, servers);
            }
        });
        this.serversByPath = Collections.unmodifiableMap(byPath);
    }

    public Optional<McpServerRuntime> find(String path) {
        return Optional.ofNullable(path == null ? null : serversByPath.get(path));
    }

    public Collection<McpServerRuntime> all() {
        return serversByPath.values();
    }
}
