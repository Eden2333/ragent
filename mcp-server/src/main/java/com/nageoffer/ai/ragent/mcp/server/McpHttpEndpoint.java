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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通用 MCP Streamable HTTP 端点
 * <p>
 * 一个端点承载全部 MCP Server：按请求路径（/mcp 或 /mcp/{领域}-mcp）路由到对应 Server，
 * 依次执行：Server 解析 → 按 Server 鉴权 → HTTP 头校验 → JSON 解析 → 批量消息版本校验 → 协议分发。
 * <p>
 * 只实现 POST 请求-响应模式，始终返回 application/json；不提供服务端 SSE 流，也不做会话管理（GET/DELETE 返回 405）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpHttpEndpoint {

    public static final String BASE_PATH = "/mcp";

    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private final McpServerRegistry registry;

    private final McpProtocolHandler handler;

    private final ObjectMapper objectMapper;

    @PostMapping({BASE_PATH, BASE_PATH + "/{serverKey}"})
    public ResponseEntity<byte[]> handlePost(@PathVariable(name = "serverKey", required = false) String serverKey,
                                             HttpServletRequest request,
                                             @RequestBody(required = false) byte[] body) {
        try {
            Optional<McpServerRuntime> found = resolve(serverKey);
            if (found.isEmpty()) {
                return json(HttpStatus.NOT_FOUND, handler.error(null, McpProtocolHandler.INVALID_REQUEST, "Unknown MCP server"));
            }
            McpServerRuntime server = found.get();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            Optional<McpPrincipal> principal = server.authenticator().authenticate(authorization);
            if (principal.isEmpty()) {
                return unauthorized(server, authorization != null);
            }
            if (!acceptsJson(request.getHeader(HttpHeaders.ACCEPT))) {
                return json(HttpStatus.NOT_ACCEPTABLE, handler.error(null, McpProtocolHandler.INVALID_REQUEST,
                        "Not Acceptable: client must accept application/json"));
            }
            if (!isJsonContentType(request.getContentType())) {
                return json(HttpStatus.UNSUPPORTED_MEDIA_TYPE, handler.error(null, McpProtocolHandler.INVALID_REQUEST,
                        "Unsupported Media Type: Content-Type must be application/json"));
            }
            String headerVersion = trimToNull(request.getHeader(PROTOCOL_VERSION_HEADER));
            if (headerVersion != null && !McpProtocolVersions.isSupported(headerVersion)) {
                return json(HttpStatus.BAD_REQUEST, handler.error(null, McpProtocolHandler.INVALID_REQUEST,
                        "Unsupported MCP-Protocol-Version: supported " + McpProtocolVersions.SUPPORTED));
            }
            String effectiveVersion = headerVersion != null ? headerVersion : McpProtocolVersions.ASSUMED_WHEN_ABSENT;

            JsonNode root = parse(body);
            if (root == null) {
                return json(HttpStatus.BAD_REQUEST, handler.error(null, McpProtocolHandler.PARSE_ERROR, "Parse error"));
            }
            if (root.isArray()) {
                return handleBatch(server, root, principal.get(), effectiveVersion);
            }
            Map<String, Object> response = handler.handle(server, root, principal.get());
            if (response == null) {
                // 仅包含通知或响应：202 Accepted，无响应体
                return ResponseEntity.accepted().build();
            }
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            log.error("MCP 请求处理异常: path={}, error={}", request.getRequestURI(), e.getClass().getName());
            try {
                return json(HttpStatus.INTERNAL_SERVER_ERROR, handler.error(null, McpProtocolHandler.INTERNAL_ERROR, "Internal error"));
            } catch (Exception ignored) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
    }

    /**
     * 不提供服务端发起的 SSE 流，也不支持显式终止会话；先鉴权再返回 405，未授权方拿不到端点能力信息
     */
    @RequestMapping(value = {BASE_PATH, BASE_PATH + "/{serverKey}"}, method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<byte[]> handleUnsupported(@PathVariable(name = "serverKey", required = false) String serverKey,
                                                    HttpServletRequest request) throws Exception {
        Optional<McpServerRuntime> found = resolve(serverKey);
        if (found.isEmpty()) {
            return json(HttpStatus.NOT_FOUND, handler.error(null, McpProtocolHandler.INVALID_REQUEST, "Unknown MCP server"));
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (found.get().authenticator().authenticate(authorization).isEmpty()) {
            return unauthorized(found.get(), authorization != null);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private ResponseEntity<byte[]> handleBatch(McpServerRuntime server, JsonNode root, McpPrincipal principal,
                                               String effectiveVersion) throws Exception {
        if (!McpProtocolVersions.supportsBatch(effectiveVersion)) {
            return json(HttpStatus.BAD_REQUEST, handler.error(null, McpProtocolHandler.INVALID_REQUEST,
                    "JSON-RPC batch is not supported in MCP protocol version " + effectiveVersion));
        }
        if (root.isEmpty()) {
            return json(HttpStatus.BAD_REQUEST, handler.error(null, McpProtocolHandler.INVALID_REQUEST, "Invalid Request"));
        }
        List<Map<String, Object>> responses = new ArrayList<>();
        for (JsonNode message : root) {
            Map<String, Object> response = handler.handle(server, message, principal);
            if (response != null) {
                responses.add(response);
            }
        }
        if (responses.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        return json(HttpStatus.OK, responses);
    }

    private Optional<McpServerRuntime> resolve(String serverKey) {
        String path = serverKey == null ? BASE_PATH : BASE_PATH + "/" + serverKey;
        return registry.find(path);
    }

    private ResponseEntity<byte[]> unauthorized(McpServerRuntime server, boolean headerProvided) throws Exception {
        log.debug("MCP Server[{}] 鉴权失败", server.name());
        String challenge = "Bearer realm=\"" + server.realm() + "\"" + (headerProvided ? ", error=\"invalid_token\"" : "");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(handler.error(null, McpProtocolHandler.UNAUTHORIZED, "Unauthorized")));
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root == null || root.isMissingNode() ? null : root;
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<byte[]> json(HttpStatus status, Object payload) throws Exception {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(payload));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
