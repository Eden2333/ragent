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

import java.util.Optional;

/**
 * MCP Server 的鉴权策略，按 Server 维度配置
 */
@FunctionalInterface
public interface McpAuthenticator {

    /**
     * 校验请求的 Authorization 头
     *
     * @param authorizationHeader Authorization 请求头，可能为 null
     * @return 校验通过时返回调用方；为空表示拒绝（HTTP 401）
     */
    Optional<McpPrincipal> authenticate(String authorizationHeader);

    /**
     * 是否要求客户端携带凭据，用于决定 401 响应中的 WWW-Authenticate 提示
     */
    default boolean requiresCredentials() {
        return true;
    }

    /**
     * 不做鉴权，所有请求以匿名身份通过
     */
    static McpAuthenticator anonymous() {
        return new McpAuthenticator() {

            @Override
            public Optional<McpPrincipal> authenticate(String authorizationHeader) {
                return Optional.of(McpPrincipal.ANONYMOUS);
            }

            @Override
            public boolean requiresCredentials() {
                return false;
            }
        };
    }
}
