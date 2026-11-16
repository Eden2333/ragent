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

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;

/**
 * 领域 MCP Server 的工具
 * <p>
 * 与 {@link com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor} 不同：实现本接口的工具不会被自动发现，
 * 只会出现在显式声明它的 {@link McpServerDefinition} 中，执行时可拿到调用方身份做授权校验
 */
public interface McpTool {

    MCPToolDefinition getToolDefinition();

    /**
     * 执行工具
     *
     * @param request   工具请求
     * @param principal 鉴权通过的调用方，无鉴权 Server 为 {@link McpPrincipal#ANONYMOUS}
     * @return 工具响应；失败时返回 success=false 的响应，错误信息会原样返回给模型，不得包含敏感信息
     */
    MCPToolResponse execute(MCPToolRequest request, McpPrincipal principal);

    default String getToolId() {
        return getToolDefinition().getToolId();
    }
}
