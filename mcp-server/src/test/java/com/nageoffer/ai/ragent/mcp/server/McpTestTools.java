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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 测试用工具与 Server 定义
 */
final class McpTestTools {

    private McpTestTools() {
    }

    static McpTool tool(String name, BiFunction<MCPToolRequest, McpPrincipal, MCPToolResponse> fn) {
        return tool(name, Map.of(), fn);
    }

    static McpTool tool(String name, Map<String, MCPToolDefinition.ParameterDef> params,
                        BiFunction<MCPToolRequest, McpPrincipal, MCPToolResponse> fn) {
        MCPToolDefinition def = MCPToolDefinition.builder()
                .toolId(name)
                .description("test tool " + name)
                .parameters(new LinkedHashMap<>(params))
                .requireUserId(false)
                .build();
        return new McpTool() {
            @Override
            public MCPToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public MCPToolResponse execute(MCPToolRequest request, McpPrincipal principal) {
                return fn.apply(request, principal);
            }
        };
    }

    /**
     * 回显工具：返回 msg 参数与调用方名称
     */
    static McpTool echo() {
        Map<String, MCPToolDefinition.ParameterDef> params = new LinkedHashMap<>();
        params.put("msg", MCPToolDefinition.ParameterDef.builder().type("string").description("消息").required(true).build());
        params.put("mode", MCPToolDefinition.ParameterDef.builder().type("string").enumValues(List.of("a", "b"))
                .defaultValue("a").build());
        return tool("echo", params, (req, p) ->
                MCPToolResponse.success("echo", "echo:" + req.getStringParameter("msg") + "|caller:" + p.name()));
    }

    /**
     * 阻塞工具：进入后通知 entered，等待 release 后返回
     */
    static McpTool blocking(CountDownLatch entered, CountDownLatch release) {
        return tool("block", (req, p) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MCPToolResponse.success("block", "done");
        });
    }

    static McpServerDefinition server(String name, String path, List<McpTool> tools) {
        return server(name, path, tools, McpAuthenticator.anonymous(), 32, null);
    }

    static McpServerDefinition server(String name, String path, List<McpTool> tools, McpAuthenticator authenticator,
                                      int maxConcurrentCalls, String instructions) {
        return new McpServerDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public List<? extends McpTool> tools() {
                return tools;
            }

            @Override
            public String instructions() {
                return instructions;
            }

            @Override
            public McpAuthenticator authenticator() {
                return authenticator;
            }

            @Override
            public int maxConcurrentCalls() {
                return maxConcurrentCalls;
            }
        };
    }
}
