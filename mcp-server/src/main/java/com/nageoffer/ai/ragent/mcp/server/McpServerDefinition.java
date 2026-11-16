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
 * 领域 MCP Server 声明
 * <p>
 * 每个领域实现一个本接口的 Spring Bean，只声明「自己是什么」：挂载路径、身份、使用说明、工具集、鉴权与并发上限。
 * 版本协商、JSON-RPC 校验、错误码、批量消息、通知 202 等协议细节由 {@link McpProtocolHandler} 统一处理。
 * <p>
 * 路径约定：默认 Server 为 {@code /mcp}，领域 Server 为 {@code /mcp/{领域}-mcp}
 */
public interface McpServerDefinition {

    /**
     * Server 唯一名称，用于日志、审计与 401 响应中的 realm
     */
    String name();

    /**
     * 挂载路径，必须匹配 {@code /mcp} 或 {@code /mcp/{小写字母数字与中划线}}
     */
    String path();

    /**
     * 本 Server 的工具集，启动时固定（因此 capabilities.tools.listChanged 为 false）
     */
    List<? extends McpTool> tools();

    /**
     * initialize 响应中 serverInfo.name
     */
    default String serverInfoName() {
        return "ragent-" + name() + "-mcp";
    }

    default String version() {
        return "0.0.1";
    }

    /**
     * initialize 响应中的 instructions，用于告诉模型如何使用本 Server 的工具；为空则不返回
     */
    default String instructions() {
        return null;
    }

    /**
     * 鉴权策略，默认不鉴权
     */
    default McpAuthenticator authenticator() {
        return McpAuthenticator.anonymous();
    }

    /**
     * 本 Server 同时执行中的 tools/call 上限（舱壁），超出时快速失败，避免一个领域占满 Web 线程拖垮其他领域
     */
    default int maxConcurrentCalls() {
        return 32;
    }
}
