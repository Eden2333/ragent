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

import java.util.List;

/**
 * MCP 协议版本常量与协商规则（仅覆盖基于 initialize 握手的修订版）
 */
public final class McpProtocolVersions {

    /**
     * 支持的协议版本，按从新到旧排列
     */
    public static final List<String> SUPPORTED = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /**
     * 服务端支持的最新版本；客户端请求的版本不受支持时回落到该版本
     */
    public static final String LATEST = SUPPORTED.get(0);

    /**
     * Streamable HTTP 规范：请求未携带 MCP-Protocol-Version 头时，服务端应假定为 2025-03-26
     */
    public static final String ASSUMED_WHEN_ABSENT = "2025-03-26";

    /**
     * 2025-06-18 修订版移除了 JSON-RPC 批量消息
     */
    private static final String BATCH_REMOVED_SINCE = "2025-06-18";

    private McpProtocolVersions() {
    }

    public static boolean isSupported(String version) {
        return version != null && SUPPORTED.contains(version);
    }

    /**
     * 版本协商：支持客户端请求的版本则原样返回，否则返回服务端支持的最新版本
     */
    public static String negotiate(String requested) {
        return isSupported(requested) ? requested : LATEST;
    }

    /**
     * 该版本是否允许 JSON-RPC 批量消息（版本号为 YYYY-MM-DD，可按字符串比较）
     */
    public static boolean supportsBatch(String version) {
        return version != null && version.compareTo(BATCH_REMOVED_SINCE) < 0;
    }
}
