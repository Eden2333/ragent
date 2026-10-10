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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * /mcp/mysql-mcp Bearer token 鉴权过滤器
 * <p>
 * 不加 @Component，仅通过 FilterRegistrationBean 注册到 MySQL MCP 路径；对所有 HTTP 方法生效
 */
@Slf4j
@RequiredArgsConstructor
public class MySQLMcpAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = MySQLMcpAuthFilter.class.getName() + ".PRINCIPAL";

    private final MySQLMcpTokenAuthenticator authenticator;

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<MySQLMcpPrincipal> principal = authenticator.authenticate(header);
        if (principal.isEmpty()) {
            log.debug("MySQL MCP 鉴权失败, remoteAddr={}", request.getRemoteAddr());
            writeUnauthorized(response, header != null);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, boolean headerProvided) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", headerProvided
                ? "Bearer realm=\"mysql-mcp\", error=\"invalid_token\""
                : "Bearer realm=\"mysql-mcp\"");
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", -32001);
        error.put("message", "Unauthorized");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", error);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
    }
}
