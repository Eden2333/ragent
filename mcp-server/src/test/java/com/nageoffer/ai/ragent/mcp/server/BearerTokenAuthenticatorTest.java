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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearerTokenAuthenticatorTest {

    private final BearerTokenAuthenticator auth = new BearerTokenAuthenticator("test", List.of(
            new BearerTokenAuthenticator.TokenSpec("alice", "token-a", Set.of("ds1")),
            new BearerTokenAuthenticator.TokenSpec("bob", "token-b", Set.of("ds1", "ds2")),
            new BearerTokenAuthenticator.TokenSpec("placeholder", BearerTokenAuthenticator.PLACEHOLDER_TOKEN, Set.of("ds1")),
            new BearerTokenAuthenticator.TokenSpec("blank", "  ", Set.of("ds1"))));

    @Test
    void validTokenReturnsPrincipalWithGrants() {
        McpPrincipal p = auth.authenticate("Bearer token-b").orElseThrow();
        assertEquals("bob", p.name());
        assertEquals(Set.of("ds1", "ds2"), p.grants());
        assertTrue(p.hasGrant("ds2"));
        assertFalse(p.hasGrant("ds3"));
    }

    @Test
    void prefixIsCaseInsensitiveAndTokenIsTrimmed() {
        assertEquals("alice", auth.authenticate("  bearer   token-a  ").orElseThrow().name());
    }

    @Test
    void rejectsMissingMalformedOrWrongToken() {
        assertTrue(auth.authenticate(null).isEmpty());
        assertTrue(auth.authenticate("").isEmpty());
        assertTrue(auth.authenticate("token-a").isEmpty());
        assertTrue(auth.authenticate("Basic token-a").isEmpty());
        assertTrue(auth.authenticate("Bearer ").isEmpty());
        assertTrue(auth.authenticate("Bearer token-c").isEmpty());
        assertTrue(auth.authenticate("Bearer token-A").isEmpty(), "token 区分大小写");
    }

    @Test
    void placeholderAndBlankTokensAreDisabled() {
        assertTrue(auth.authenticate("Bearer " + BearerTokenAuthenticator.PLACEHOLDER_TOKEN).isEmpty());
        assertTrue(auth.authenticate("Bearer   ").isEmpty());
    }

    @Test
    void noTokensRejectsEverything() {
        BearerTokenAuthenticator empty = new BearerTokenAuthenticator("empty", List.of());
        assertTrue(empty.authenticate("Bearer anything").isEmpty());
        assertTrue(empty.requiresCredentials());
    }

    @Test
    void tokenSpecToStringDoesNotLeakToken() {
        String text = new BearerTokenAuthenticator.TokenSpec("alice", "super-secret", Set.of()).toString();
        assertFalse(text.contains("super-secret"));
    }

    @Test
    void anonymousAuthenticatorAlwaysPasses() {
        McpAuthenticator anonymous = McpAuthenticator.anonymous();
        assertEquals(McpPrincipal.ANONYMOUS, anonymous.authenticate(null).orElseThrow());
        assertFalse(anonymous.requiresCredentials());
        assertTrue(McpPrincipal.ANONYMOUS.grants().isEmpty());
    }
}
