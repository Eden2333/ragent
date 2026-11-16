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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpHttpEndpointTest {

    private static final String TOKEN = "Bearer secret-token";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        McpTool grants = McpTestTools.tool("whoami", (r, p) ->
                MCPToolResponse.success("whoami", p.name() + ":" + p.grants()));
        McpServerRegistry registry = new McpServerRegistry(List.of(
                McpTestTools.server("default", "/mcp", List.of(McpTestTools.echo())),
                McpTestTools.server("secure", "/mcp/secure-mcp", List.of(grants),
                        new BearerTokenAuthenticator("secure", List.of(
                                new BearerTokenAuthenticator.TokenSpec("svc", "secret-token", Set.of("ds1")))),
                        4, null)));
        mvc = MockMvcBuilders.standaloneSetup(new McpHttpEndpoint(registry, new McpProtocolHandler(mapper), mapper)).build();
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    @Test
    void defaultServerWorksWithoutTokenAndNotificationReturns202() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("initialize", "{\"protocolVersion\":\"2025-03-26\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void secureServerRequiresToken() throws Exception {
        mvc.perform(post("/mcp/secure-mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("tools/list", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"secure-mcp\""))
                .andExpect(jsonPath("$.error.code").value(-32001));
        mvc.perform(post("/mcp/secure-mcp").header("Authorization", "Bearer wrong").contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/list", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        mvc.perform(post("/mcp/secure-mcp").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/call", "{\"name\":\"whoami\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("svc:[ds1]"));
    }

    @Test
    void toolsAreIsolatedPerServer() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("tools/call", "{\"name\":\"whoami\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(post("/mcp/secure-mcp").header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/list", null)))
                .andExpect(jsonPath("$.result.tools.length()").value(1))
                .andExpect(jsonPath("$.result.tools[0].name").value("whoami"));
    }

    @Test
    void getAndDeleteAreAuthenticatedThenRejected() throws Exception {
        mvc.perform(get("/mcp/secure-mcp")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mcp/secure-mcp").header("Authorization", TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mvc.perform(delete("/mcp")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownServerReturns404() throws Exception {
        mvc.perform(post("/mcp/unknown-mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("ping", null)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/mcp/unknown-mcp")).andExpect(status().isNotFound());
    }

    @Test
    void httpHeaderValidation() throws Exception {
        mvc.perform(post("/mcp").accept(MediaType.TEXT_HTML).contentType(MediaType.APPLICATION_JSON).content(rpc("ping", null)))
                .andExpect(status().isNotAcceptable());
        mvc.perform(post("/mcp").contentType(MediaType.TEXT_PLAIN).content(rpc("ping", null)))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mcp").header("MCP-Protocol-Version", "1999-01-01").contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("ping", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/mcp").header("MCP-Protocol-Version", "2025-11-25").contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("ping", null)))
                .andExpect(status().isOk());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void batchFollowsNegotiatedProtocolVersion() throws Exception {
        String batch = "[" + rpc("ping", null) + ",{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]";
        // 未携带版本头时按 2025-03-26 处理，该版本支持批量
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/mcp").header("MCP-Protocol-Version", "2025-06-18").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32600));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"))
                .andExpect(status().isAccepted());
    }
}
