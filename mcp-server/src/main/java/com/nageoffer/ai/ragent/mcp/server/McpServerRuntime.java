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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 单个 MCP Server 的运行时：启动时校验并固定的工具表、鉴权策略与舱壁信号量
 */
public final class McpServerRuntime {

    private final McpServerDefinition definition;

    private final Map<String, McpTool> tools;

    private final McpAuthenticator authenticator;

    private final int maxConcurrentCalls;

    private final Semaphore bulkhead;

    McpServerRuntime(McpServerDefinition definition, Map<String, McpTool> tools,
                     McpAuthenticator authenticator, int maxConcurrentCalls) {
        this.definition = definition;
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.authenticator = authenticator;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.bulkhead = new Semaphore(maxConcurrentCalls);
    }

    public String name() {
        return definition.name();
    }

    public String path() {
        return definition.path();
    }

    public McpServerDefinition definition() {
        return definition;
    }

    public McpAuthenticator authenticator() {
        return authenticator;
    }

    public McpTool tool(String name) {
        return name == null ? null : tools.get(name);
    }

    public Collection<McpTool> tools() {
        return tools.values();
    }

    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * 401 响应中 WWW-Authenticate 的 realm，取路径最后一段（如 mysql-mcp）
     */
    public String realm() {
        String path = definition.path();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 非阻塞获取执行许可，失败时调用方应快速返回「繁忙」，不占用 Web 线程排队
     */
    boolean tryAcquire() {
        return bulkhead.tryAcquire();
    }

    void release() {
        bulkhead.release();
    }

    /**
     * 当前可用许可数（用于测试与观测）
     */
    public int availablePermits() {
        return bulkhead.availablePermits();
    }
}
