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

package com.nageoffer.ai.ragent.mcp.mysql.auth;

import com.nageoffer.ai.ragent.mcp.mysql.config.MySQLMcpProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL MCP Bearer token 校验
 * <p>
 * token 视为不透明字符串，使用常量时间比较
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLMcpTokenAuthenticator {

    /**
     * 示例配置中的公开占位值，禁止作为有效 token
     */
    private static final String PLACEHOLDER_TOKEN = "change-me";

    private static final String BEARER_PREFIX = "Bearer ";

    private final MySQLMcpProperties properties;

    private List<TokenEntry> entries = List.of();

    @PostConstruct
    public void init() {
        List<TokenEntry> loaded = new ArrayList<>();
        int index = 0;
        for (MySQLMcpProperties.TokenProperties tokenProps : properties.getTokens()) {
            String name = tokenProps.getName() == null || tokenProps.getName().isBlank() ? "token#" + index : tokenProps.getName();
            index++;
            String token = tokenProps.getToken();
            if (token == null || token.isBlank() || PLACEHOLDER_TOKEN.equals(token.trim())) {
                log.warn("MySQL MCP token[{}] 未配置或为占位值，已禁用", name);
                continue;
            }
            List<String> datasources = tokenProps.getDatasources() == null ? List.of() : tokenProps.getDatasources();
            for (String ds : datasources) {
                if (!properties.getDatasources().containsKey(ds)) {
                    log.warn("MySQL MCP token[{}] 引用了不存在的数据源: {}", name, ds);
                }
            }
            loaded.add(new TokenEntry(name, token.trim().getBytes(StandardCharsets.UTF_8), Set.copyOf(datasources)));
        }
        this.entries = List.copyOf(loaded);
        if (entries.isEmpty()) {
            log.warn("/mcp/mysql-mcp 无可用 token，所有请求将被拒绝");
        } else {
            log.info("MySQL MCP 已加载 {} 个有效 token", entries.size());
        }
    }

    /**
     * 校验 Authorization 头
     *
     * @param authorizationHeader Authorization 请求头
     * @return 校验通过时返回调用方
     */
    public Optional<MySQLMcpPrincipal> authenticate(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String header = authorizationHeader.trim();
        if (header.length() < BEARER_PREFIX.length() || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        byte[] provided = token.getBytes(StandardCharsets.UTF_8);
        TokenEntry matched = null;
        // 遍历全部条目，不提前退出
        for (TokenEntry entry : entries) {
            if (MessageDigest.isEqual(provided, entry.tokenBytes()) && matched == null) {
                matched = entry;
            }
        }
        return matched == null ? Optional.empty() : Optional.of(new MySQLMcpPrincipal(matched.name(), matched.datasources()));
    }

    private record TokenEntry(String name, byte[] tokenBytes, Set<String> datasources) {
    }
}
