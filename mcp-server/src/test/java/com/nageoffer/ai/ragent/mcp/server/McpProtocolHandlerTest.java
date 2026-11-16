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
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private McpProtocolHandler handler;

    private McpServerRuntime server;

    @BeforeEach
    void setUp() {
        handler = new McpProtocolHandler(mapper);
        List<McpTool> tools = List.of(
                McpTestTools.echo(),
                McpTestTools.tool("noargs", (r, p) -> MCPToolResponse.success("noargs", "ok")),
                McpTestTools.tool("fail", (r, p) -> MCPToolResponse.error("fail", "BAD", "参数不合法")),
                McpTestTools.tool("crash", (r, p) -> {
                    throw new IllegalStateException("password=secret123 jdbc:mysql://10.0.0.1");
                }),
                McpTestTools.tool("safe", (r, p) -> {
                    throw new McpToolException("缺少参数 x");
                }),
                McpTestTools.tool("nullresp", (r, p) -> null));
        server = new McpServerRegistry(List.of(McpTestTools.server("demo", "/mcp/demo-mcp", tools,
                McpAuthenticator.anonymous(), 32, "先调用 echo"))).find("/mcp/demo-mcp").orElseThrow();
    }

    private JsonNode call(String json) {
        return call(server, json, McpPrincipal.ANONYMOUS);
    }

    private JsonNode call(McpServerRuntime target, String json, McpPrincipal principal) {
        try {
            Map<String, Object> resp = handler.handle(target, mapper.readTree(json), principal);
            return resp == null ? null : mapper.valueToTree(resp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void initializeEchoesSupportedVersion() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals("2025-06-18", r.at("/result/protocolVersion").asText());
        assertEquals("ragent-demo-mcp", r.at("/result/serverInfo/name").asText());
        assertEquals("先调用 echo", r.at("/result/instructions").asText());
        assertFalse(r.at("/result/capabilities/tools/listChanged").asBoolean(true));
        assertFalse(r.has("error"), "成功响应不应包含 error 字段");
    }

    @Test
    void initializeFallsBackToLatestForUnknownVersion() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-02-28\"}}");
        assertEquals(McpProtocolVersions.LATEST, r.at("/result/protocolVersion").asText());
    }

    @Test
    void initializeOmitsBlankInstructions() {
        McpServerRuntime plain = new McpServerRegistry(List.of(McpTestTools.server("plain", "/mcp", List.of())))
                .find("/mcp").orElseThrow();
        JsonNode r = call(plain, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", McpPrincipal.ANONYMOUS);
        assertFalse(r.get("result").has("instructions"));
        assertEquals(McpProtocolVersions.LATEST, r.at("/result/protocolVersion").asText());
    }

    @Test
    void pingReturnsEmptyResult() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":\"p1\",\"method\":\"ping\"}");
        assertEquals("p1", r.get("id").asText());
        assertTrue(r.get("result").isObject());
        assertEquals(0, r.get("result").size());
    }

    @Test
    void notificationsAndClientResponsesProduceNoResponse() {
        assertNull(call("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(call("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"));
    }

    @Test
    void invalidRequestsReturnStandardCodes() {
        assertEquals(-32600, call("1").at("/error/code").asInt());
        assertEquals(-32600, call("{\"id\":1,\"method\":\"ping\"}").at("/error/code").asInt());
        assertEquals(-32600, call("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}").at("/error/code").asInt());
        assertEquals(-32600, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":123}").at("/error/code").asInt());
        assertEquals(-32600, call("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}").at("/error/code").asInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":[]}").at("/error/code").asInt());
        JsonNode unknown = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertEquals(-32601, unknown.at("/error/code").asInt());
        assertFalse(unknown.has("result"), "错误响应不应包含 result 字段");
    }

    @Test
    void toolsListUsesJsonSchemaKeywords() {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").at("/result/tools");
        assertEquals(6, tools.size());
        JsonNode echo = tools.get(0);
        assertEquals("echo", echo.get("name").asText());
        JsonNode schema = echo.get("inputSchema");
        assertEquals("object", schema.get("type").asText());
        assertEquals("msg", schema.get("required").get(0).asText());
        assertEquals("b", schema.at("/properties/mode/enum/1").asText());
        assertEquals("a", schema.at("/properties/mode/default").asText());
        assertFalse(schema.at("/properties/mode").has("enumValues"));
        assertFalse(schema.at("/properties/msg").has("enum"), "空枚举不应输出");
        assertFalse(schema.has("additionalProperties"));

        JsonNode noargs = tools.get(1).get("inputSchema");
        assertFalse(noargs.has("required"));
        assertFalse(noargs.get("additionalProperties").asBoolean(true), "无参数工具应声明 additionalProperties=false");
    }

    @Test
    void toolsCallPassesArgumentsAndPrincipal() {
        JsonNode r = call(server, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"msg\":\"hi\"}}}",
                new McpPrincipal("alice", null));
        assertFalse(r.at("/result/isError").asBoolean(true));
        assertEquals("echo:hi|caller:alice", r.at("/result/content/0/text").asText());
        assertEquals("text", r.at("/result/content/0/type").asText());
    }

    @Test
    void toolsCallProtocolErrors() {
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}").at("/error/code").asInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}").at("/error/code").asInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":[1]}}").at("/error/code").asInt());
    }

    @Test
    void toolFailureIsReportedInResultWithErrorMessage() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"fail\"}}");
        assertTrue(r.at("/result/isError").asBoolean());
        assertEquals("参数不合法", r.at("/result/content/0/text").asText());
        assertFalse(r.has("error"));
    }

    @Test
    void unexpectedExceptionIsSanitized() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"crash\"}}");
        assertTrue(r.at("/result/isError").asBoolean());
        String text = r.at("/result/content/0/text").asText();
        assertEquals(McpProtocolHandler.GENERIC_TOOL_ERROR, text);
        assertFalse(text.contains("secret123"));
        assertFalse(text.contains("10.0.0.1"));
    }

    @Test
    void safeToolExceptionMessageIsReturned() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"safe\"}}");
        assertTrue(r.at("/result/isError").asBoolean());
        assertEquals("缺少参数 x", r.at("/result/content/0/text").asText());
    }

    @Test
    void nullToolResponseBecomesGenericError() {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nullresp\"}}");
        assertTrue(r.at("/result/isError").asBoolean());
        assertEquals(McpProtocolHandler.GENERIC_TOOL_ERROR, r.at("/result/content/0/text").asText());
    }

    @Test
    void bulkheadRejectsCallsBeyondLimitAndReleasesPermits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        McpServerRuntime limited = new McpServerRegistry(List.of(McpTestTools.server("limited", "/mcp/limited-mcp",
                List.of(McpTestTools.blocking(entered, release)), McpAuthenticator.anonymous(), 1, null)))
                .find("/mcp/limited-mcp").orElseThrow();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"block\"}}";

        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> call(limited, body, McpPrincipal.ANONYMOUS));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(0, limited.availablePermits());

        JsonNode rejected = call(limited, body, McpPrincipal.ANONYMOUS);
        assertTrue(rejected.at("/result/isError").asBoolean());
        assertTrue(rejected.at("/result/content/0/text").asText().contains("繁忙"));

        release.countDown();
        JsonNode done = first.get(5, TimeUnit.SECONDS);
        assertFalse(done.at("/result/isError").asBoolean(true));
        assertEquals(1, limited.availablePermits(), "执行结束后应归还许可");
    }

    @Test
    void bulkheadReleasesPermitWhenToolThrows() {
        McpServerRuntime limited = new McpServerRegistry(List.of(McpTestTools.server("limited", "/mcp/limited-mcp",
                List.of(McpTestTools.tool("crash", (r, p) -> {
                    throw new IllegalStateException("x");
                })), McpAuthenticator.anonymous(), 1, null)))
                .find("/mcp/limited-mcp").orElseThrow();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"crash\"}}";
        call(limited, body, McpPrincipal.ANONYMOUS);
        call(limited, body, McpPrincipal.ANONYMOUS);
        assertEquals(1, limited.availablePermits());
    }
}
