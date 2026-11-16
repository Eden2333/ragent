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

import java.util.Set;

/**
 * 鉴权通过的调用方
 *
 * @param name   调用方名称（如 token 名称），仅用于日志与审计，不含凭据
 * @param grants 授权范围，具体含义由各领域 Server 自行解释（如 MySQL Server 中表示可访问的数据源）
 */
public record McpPrincipal(String name, Set<String> grants) {

    /**
     * 无鉴权 Server 使用的匿名调用方，不具备任何授权范围
     */
    public static final McpPrincipal ANONYMOUS = new McpPrincipal("anonymous", Set.of());

    public McpPrincipal {
        grants = grants == null ? Set.of() : Set.copyOf(grants);
    }

    public boolean hasGrant(String grant) {
        return grant != null && grants.contains(grant);
    }
}
