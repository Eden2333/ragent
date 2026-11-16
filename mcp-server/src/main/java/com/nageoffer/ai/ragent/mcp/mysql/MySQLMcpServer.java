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

package com.nageoffer.ai.ragent.mcp.mysql;

import com.nageoffer.ai.ragent.mcp.mysql.config.MySQLMcpProperties;
import com.nageoffer.ai.ragent.mcp.mysql.tool.MySQLMcpTool;
import com.nageoffer.ai.ragent.mcp.server.BearerTokenAuthenticator;
import com.nageoffer.ai.ragent.mcp.server.McpAuthenticator;
import com.nageoffer.ai.ragent.mcp.server.McpServerDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 只读 MySQL MCP Server（/mcp/mysql-mcp）
 * <p>
 * 只声明领域信息：工具集（全部 {@link MySQLMcpTool}）、使用说明、Bearer token 鉴权（token 绑定数据源）与并发上限，
 * 协议处理复用 {@link com.nageoffer.ai.ragent.mcp.server.McpProtocolHandler}
 */
@Slf4j
@Component
public class MySQLMcpServer implements McpServerDefinition {

    private final List<MySQLMcpTool> tools;

    private final McpAuthenticator authenticator;

    private final int maxConcurrentCalls;

    public MySQLMcpServer(List<MySQLMcpTool> tools, MySQLMcpProperties properties) {
        this.tools = List.copyOf(tools);
        this.maxConcurrentCalls = properties.getMaxConcurrentCalls();
        List<BearerTokenAuthenticator.TokenSpec> specs = properties.getTokens().stream()
                .map(t -> {
                    List<String> datasources = t.getDatasources() == null ? List.of() : t.getDatasources();
                    datasources.stream()
                            .filter(ds -> !properties.getDatasources().containsKey(ds))
                            .forEach(ds -> log.warn("MySQL MCP token[{}] 引用了不存在的数据源: {}", t.getName(), ds));
                    return new BearerTokenAuthenticator.TokenSpec(t.getName(), t.getToken(), Set.copyOf(datasources));
                })
                .toList();
        this.authenticator = new BearerTokenAuthenticator(name(), specs);
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String path() {
        return "/mcp/mysql-mcp";
    }

    @Override
    public List<MySQLMcpTool> tools() {
        return tools;
    }

    @Override
    public String instructions() {
        return "只读 MySQL 工具：先调用 mysql_list_datasources 获取数据源，再查询表结构或执行 DQL";
    }

    @Override
    public McpAuthenticator authenticator() {
        return authenticator;
    }

    @Override
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }
}
