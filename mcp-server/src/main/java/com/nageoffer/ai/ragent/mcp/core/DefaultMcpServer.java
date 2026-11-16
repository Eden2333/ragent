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

package com.nageoffer.ai.ragent.mcp.core;

import com.nageoffer.ai.ragent.mcp.server.McpPrincipal;
import com.nageoffer.ai.ragent.mcp.server.McpServerDefinition;
import com.nageoffer.ai.ragent.mcp.server.McpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认 MCP Server（/mcp，无鉴权）
 * <p>
 * 保持原有扩展方式：实现 {@link MCPToolExecutor} 的 Bean 会被 {@link MCPToolRegistry} 自动发现并挂到本 Server。
 * 需要鉴权或隔离的工具请实现 {@link McpTool} 并放进对应的领域 Server，不要实现 {@link MCPToolExecutor}。
 */
@Component
@RequiredArgsConstructor
public class DefaultMcpServer implements McpServerDefinition {

    private final MCPToolRegistry toolRegistry;

    @Override
    public String name() {
        return "default";
    }

    @Override
    public String path() {
        return "/mcp";
    }

    @Override
    public String serverInfoName() {
        return "ragent-mcp-server";
    }

    @Override
    public List<? extends McpTool> tools() {
        return toolRegistry.listAllExecutors().stream()
                .map(DefaultMcpServer::adapt)
                .toList();
    }

    private static McpTool adapt(MCPToolExecutor executor) {
        // 同时实现两个接口的工具会被自动发现并暴露到无鉴权的 /mcp，直接阻止启动
        if (executor instanceof McpTool) {
            throw new IllegalStateException("工具 " + executor.getClass().getName()
                    + " 同时实现了 MCPToolExecutor 与 McpTool，会被暴露到无鉴权的 /mcp，请只实现其中一个");
        }
        return new ExecutorAdapter(executor);
    }

    /**
     * 将原有执行器适配为通用工具；默认 Server 无鉴权，调用方身份不参与执行
     */
    private record ExecutorAdapter(MCPToolExecutor delegate) implements McpTool {

        @Override
        public MCPToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public MCPToolResponse execute(MCPToolRequest request, McpPrincipal principal) {
            return delegate.execute(request);
        }
    }
}
