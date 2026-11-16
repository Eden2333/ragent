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

import com.nageoffer.ai.ragent.mcp.core.DefaultMCPToolRegistry;
import com.nageoffer.ai.ragent.mcp.core.DefaultMcpServer;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerRegistryTest {

    private static McpTool ok(String name) {
        return McpTestTools.tool(name, (r, p) -> MCPToolResponse.success(name, "ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/mcp", "/mcp/", "/mcp/Bad_Path", "/mcp/a/b", "/mcp/-x", "/mcpx", "mcp"})
    void rejectsInvalidPath(String path) {
        assertThrows(IllegalStateException.class,
                () -> new McpServerRegistry(List.of(McpTestTools.server("s", path, List.of()))));
    }

    @Test
    void rejectsDuplicatePathAndName() {
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of()),
                McpTestTools.server("b", "/mcp/x-mcp", List.of()))));
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of()),
                McpTestTools.server("a", "/mcp/y-mcp", List.of()))));
    }

    @Test
    void rejectsDuplicateOrInvalidToolNameWithinServer() {
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of(ok("t1"), ok("t1"))))));
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of(ok("bad name"))))));
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of(ok("x".repeat(129)))))));
    }

    @Test
    void rejectsInvalidConcurrencyAndMissingAuthenticator() {
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of(), McpAuthenticator.anonymous(), 0, null))));
        assertThrows(IllegalStateException.class, () -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/x-mcp", List.of(), null, 1, null))));
    }

    @Test
    void sameToolNameAcrossServersIsAllowed() {
        McpServerRegistry registry = assertDoesNotThrow(() -> new McpServerRegistry(List.of(
                McpTestTools.server("a", "/mcp/a-mcp", List.of(ok("query"))),
                McpTestTools.server("b", "/mcp/b-mcp", List.of(ok("query"))))));
        assertEquals(2, registry.all().size());
    }

    @Test
    void findsServerByPathAndDerivesRealm() {
        McpServerRegistry registry = new McpServerRegistry(List.of(
                McpTestTools.server("default", "/mcp", List.of(ok("a"))),
                McpTestTools.server("mysql", "/mcp/mysql-mcp", List.of(ok("b")))));
        McpServerRuntime mysql = registry.find("/mcp/mysql-mcp").orElseThrow();
        assertEquals("mysql-mcp", mysql.realm());
        assertEquals("mcp", registry.find("/mcp").orElseThrow().realm());
        assertTrue(registry.find("/mcp/none-mcp").isEmpty());
        assertTrue(registry.find(null).isEmpty());
        // 工具只属于各自的 Server
        assertTrue(registry.find("/mcp").orElseThrow().tool("b") == null);
    }

    @Test
    void defaultServerRejectsToolImplementingBothInterfaces() {
        DefaultMCPToolRegistry toolRegistry = new DefaultMCPToolRegistry(List.of(new DualTool()));
        toolRegistry.init();
        DefaultMcpServer defaultServer = new DefaultMcpServer(toolRegistry);
        IllegalStateException e = assertThrows(IllegalStateException.class, defaultServer::tools);
        assertTrue(e.getMessage().contains("无鉴权"));
    }

    @Test
    void defaultServerAdaptsExecutors() {
        DefaultMCPToolRegistry toolRegistry = new DefaultMCPToolRegistry(List.of(new PlainExecutor()));
        toolRegistry.init();
        McpServerRuntime runtime = new McpServerRegistry(List.of(new DefaultMcpServer(toolRegistry)))
                .find("/mcp").orElseThrow();
        McpTool tool = runtime.tool("plain");
        assertEquals("plain-result", tool.execute(new MCPToolRequest(), McpPrincipal.ANONYMOUS).getTextResult());
    }

    private static MCPToolDefinition def(String name) {
        return MCPToolDefinition.builder().toolId(name).description(name).parameters(Map.of()).build();
    }

    private static class PlainExecutor implements MCPToolExecutor {

        @Override
        public MCPToolDefinition getToolDefinition() {
            return def("plain");
        }

        @Override
        public MCPToolResponse execute(MCPToolRequest request) {
            return MCPToolResponse.success("plain", "plain-result");
        }
    }

    private static class DualTool implements MCPToolExecutor, McpTool {

        @Override
        public MCPToolDefinition getToolDefinition() {
            return def("dual");
        }

        @Override
        public MCPToolResponse execute(MCPToolRequest request) {
            return MCPToolResponse.success("dual", "x");
        }

        @Override
        public MCPToolResponse execute(MCPToolRequest request, McpPrincipal principal) {
            return MCPToolResponse.success("dual", "x");
        }

        @Override
        public String getToolId() {
            return "dual";
        }
    }
}
