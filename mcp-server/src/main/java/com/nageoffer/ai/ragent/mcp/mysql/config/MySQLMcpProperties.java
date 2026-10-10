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

package com.nageoffer.ai.ragent.mcp.mysql.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL MCP 端点配置（前缀 mcp.mysql）
 */
@Data
@ConfigurationProperties(prefix = "mcp.mysql")
public class MySQLMcpProperties {

    /**
     * 单条查询超时（秒）
     */
    private int queryTimeoutSeconds = 30;

    /**
     * 客户端未传 maxRows 时的默认返回行数
     */
    private int defaultMaxRows = 500;

    /**
     * maxRows 上限
     */
    private int maxRowsLimit = 2000;

    /**
     * mysql_get_table_schemas 单次最多返回表数
     */
    private int maxSchemaTables = 200;

    /**
     * 数据源，key 为数据源名称（即客户端传入的 datasource 参数）
     */
    private Map<String, DatasourceProperties> datasources = new LinkedHashMap<>();

    /**
     * 访问 token 列表
     */
    private List<TokenProperties> tokens = new ArrayList<>();

    @Data
    public static class DatasourceProperties {

        /**
         * JDBC URL，必须以 jdbc:mysql:// 开头
         */
        private String url;

        private String username;

        @ToString.Exclude
        private String password;

        /**
         * 连接池最大连接数
         */
        private int maximumPoolSize = 5;
    }

    @Data
    public static class TokenProperties {

        /**
         * token 名称/归属人，仅用于日志
         */
        private String name;

        @ToString.Exclude
        private String token;

        /**
         * 该 token 可访问的数据源名称
         */
        private List<String> datasources = new ArrayList<>();
    }
}
