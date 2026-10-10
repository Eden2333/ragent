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

package com.nageoffer.ai.ragent.mcp.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpAuthFilter;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL MCP Streamable HTTP 端点（只读工具）
 * <p>
 * 鉴权由 {@link MySQLMcpAuthFilter} 完成；始终以 application/json 响应，不提供 SSE 流，不做会话管理
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MySQLMcpEndpoint {

    public static final String PATH = "/mcp/mysql-mcp";

    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private final MySQLMcpDispatcher dispatcher;

    private final ObjectMapper objectMapper;

    @PostMapping(PATH)
    public ResponseEntity<byte[]> handlePost(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        try {
            MySQLMcpPrincipal principal = (MySQLMcpPrincipal) request.getAttribute(MySQLMcpAuthFilter.PRINCIPAL_ATTRIBUTE);
            if (principal == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mysql-mcp\"")
                        .build();
            }
            if (!acceptsJson(request.getHeader(HttpHeaders.ACCEPT))) {
                return json(HttpStatus.NOT_ACCEPTABLE,
                        dispatcher.error(null, -32600, "Not Acceptable: client must accept application/json"));
            }
            if (!isJsonContentType(request.getContentType())) {
                return json(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        dispatcher.error(null, -32600, "Unsupported Media Type: Content-Type must be application/json"));
            }
            String protocolVersion = request.getHeader(PROTOCOL_VERSION_HEADER);
            if (protocolVersion != null && !MySQLMcpDispatcher.isSupportedProtocolVersion(protocolVersion.trim())) {
                return json(HttpStatus.BAD_REQUEST, dispatcher.error(null, -32600, "Unsupported MCP-Protocol-Version"));
            }
            if (body == null || body.length == 0) {
                return json(HttpStatus.BAD_REQUEST, dispatcher.error(null, -32700, "Parse error"));
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception e) {
                return json(HttpStatus.BAD_REQUEST, dispatcher.error(null, -32700, "Parse error"));
            }
            if (root == null || root.isMissingNode()) {
                return json(HttpStatus.BAD_REQUEST, dispatcher.error(null, -32700, "Parse error"));
            }

            if (root.isArray()) {
                if (root.isEmpty()) {
                    return json(HttpStatus.BAD_REQUEST, dispatcher.error(null, -32600, "Invalid Request"));
                }
                List<Map<String, Object>> responses = new ArrayList<>();
                for (JsonNode message : root) {
                    Map<String, Object> response = dispatcher.dispatch(message, principal);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                if (responses.isEmpty()) {
                    return ResponseEntity.accepted().build();
                }
                return json(HttpStatus.OK, responses);
            }

            Map<String, Object> response = dispatcher.dispatch(root, principal);
            if (response == null) {
                // 仅包含通知或响应：202 Accepted，无响应体
                return ResponseEntity.accepted().build();
            }
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            log.error("MySQL MCP 请求处理异常: {}", e.getClass().getName());
            try {
                return json(HttpStatus.INTERNAL_SERVER_ERROR, dispatcher.error(null, -32603, "Internal error"));
            } catch (Exception ignored) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
    }

    /**
     * 不提供服务端发起的 SSE 流
     */
    @GetMapping(PATH)
    public ResponseEntity<Void> handleGet() {
        return methodNotAllowed();
    }

    /**
     * 无会话管理，不支持显式终止会话
     */
    @DeleteMapping(PATH)
    public ResponseEntity<Void> handleDelete() {
        return methodNotAllowed();
    }

    private ResponseEntity<Void> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private ResponseEntity<byte[]> json(HttpStatus status, Object payload) throws Exception {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(payload));
    }

    private boolean acceptsJson(String accept) {
        if (accept == null || accept.isBlank()) {
            return true;
        }
        try {
            for (MediaType mediaType : MediaType.parseMediaTypes(accept)) {
                if (mediaType.includes(MediaType.APPLICATION_JSON)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (Exception e) {
            return false;
        }
    }
}
