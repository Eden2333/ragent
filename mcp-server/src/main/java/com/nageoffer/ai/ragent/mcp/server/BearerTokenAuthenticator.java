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

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 通用 Bearer token 鉴权，可被任意领域 Server 复用
 * <ul>
 *     <li>token 视为不透明字符串，使用常量时间比较，并遍历全部条目不提前退出，避免时序侧信道</li>
 *     <li>未配置或为公开占位值的 token 直接禁用；没有可用 token 时拒绝所有请求（默认拒绝）</li>
 *     <li>每个 token 绑定授权范围，由领域工具在执行时校验</li>
 * </ul>
 */
@Slf4j
public class BearerTokenAuthenticator implements McpAuthenticator {

    /**
     * 示例配置中的公开占位值，禁止作为有效 token
     */
    public static final String PLACEHOLDER_TOKEN = "change-me";

    private static final String BEARER_PREFIX = "Bearer ";

    private final List<Entry> entries;

    /**
     * @param serverName 所属 Server 名称，仅用于日志
     * @param tokens     token 配置
     */
    public BearerTokenAuthenticator(String serverName, List<TokenSpec> tokens) {
        List<Entry> loaded = new ArrayList<>();
        int index = 0;
        for (TokenSpec spec : tokens == null ? List.<TokenSpec>of() : tokens) {
            String name = spec.name() == null || spec.name().isBlank() ? "token#" + index : spec.name();
            index++;
            String token = spec.token();
            if (token == null || token.isBlank() || PLACEHOLDER_TOKEN.equals(token.trim())) {
                log.warn("MCP Server[{}] token[{}] 未配置或为占位值，已禁用", serverName, name);
                continue;
            }
            loaded.add(new Entry(name, token.trim().getBytes(StandardCharsets.UTF_8), spec.grants()));
        }
        this.entries = List.copyOf(loaded);
        if (entries.isEmpty()) {
            log.warn("MCP Server[{}] 无可用 token，所有请求将被拒绝", serverName);
        } else {
            log.info("MCP Server[{}] 已加载 {} 个有效 token", serverName, entries.size());
        }
    }

    @Override
    public Optional<McpPrincipal> authenticate(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String header = authorizationHeader.trim();
        if (header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        byte[] provided = token.getBytes(StandardCharsets.UTF_8);
        Entry matched = null;
        // 遍历全部条目，不提前退出
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(provided, entry.tokenBytes()) && matched == null) {
                matched = entry;
            }
        }
        return matched == null ? Optional.empty() : Optional.of(new McpPrincipal(matched.name(), matched.grants()));
    }

    /**
     * token 配置
     *
     * @param name   token 名称/归属人，仅用于日志
     * @param token  token 原文
     * @param grants 授权范围
     */
    public record TokenSpec(String name, String token, Set<String> grants) {

        @Override
        public String toString() {
            return "TokenSpec[name=" + name + ", grants=" + grants + "]";
        }
    }

    private record Entry(String name, byte[] tokenBytes, Set<String> grants) {
    }
}
