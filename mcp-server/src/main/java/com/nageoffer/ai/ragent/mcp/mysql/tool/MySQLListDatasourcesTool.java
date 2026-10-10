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
import com.nageoffer.ai.ragent.mcp.mysql.datasource.MySQLDataSourceManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mysql_list_datasources：列出当前 token 可访问的数据源（不含地址、账号、密码）
 */
@Component
@RequiredArgsConstructor
public class MySQLListDatasourcesTool implements MySQLMcpTool {

    private static final String TOOL_ID = "mysql_list_datasources";

    private final MySQLToolSupport support;

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("列出当前 token 可访问的 MySQL 数据源名称及其默认库，返回的 name 用作其他 MySQL 工具的 datasource 参数")
                .parameters(new LinkedHashMap<>())
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request, MySQLMcpPrincipal principal) {
        try {
            MySQLDataSourceManager manager = support.getDataSourceManager();
            List<Map<String, Object>> items = new ArrayList<>();
            principal.datasources().stream()
                    .filter(manager::isAvailable)
                    .sorted()
                    .forEach(name -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("defaultDatabase", manager.defaultDatabase(name));
                        items.add(item);
                    });
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("datasources", items);
            return MCPToolResponse.success(TOOL_ID, support.toJson(result));
        } catch (Exception e) {
            return support.error(TOOL_ID, e);
        }
    }
}
