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

/**
 * MySQL MCP 工具
 * <p>
 * 独立于 {@link com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor}，仅在 /mcp/mysql-mcp 暴露，不会混入 /mcp 的工具列表
 */
public interface MySQLMcpTool {

    MCPToolDefinition getToolDefinition();

    MCPToolResponse execute(MCPToolRequest request, MySQLMcpPrincipal principal);

    default String getToolId() {
        return getToolDefinition().getToolId();
    }
}
