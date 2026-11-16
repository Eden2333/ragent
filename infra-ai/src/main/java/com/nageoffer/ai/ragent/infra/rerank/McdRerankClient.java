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

package com.nageoffer.ai.ragent.infra.rerank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCD 网关 Rerank 客户端
 * <p>
 * 协议为 Jina/Cohere 风格：请求体顶层包含 model、query、documents、top_n，
 * 响应体顶层为 results[{index, relevance_score}]
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class McdRerankClient implements RerankClient {

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.MCD.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));
        reqBody.addProperty("query", query);
        reqBody.addProperty("top_n", topN);
        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        reqBody.add("documents", documentsArray);

        Request request = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("MCD rerank 请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "MCD rerank 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("MCD rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonArray results = respJson.has("results") && respJson.get("results").isJsonArray()
                ? respJson.getAsJsonArray("results") : null;
        if (results == null || results.size() == 0) {
            throw new ModelClientException("MCD rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            RetrievedChunk src = candidates.get(idx);
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                reranked.add(new RetrievedChunk(src.getId(), src.getText(), item.get("relevance_score").getAsFloat()));
            } else {
                reranked.add(src);
            }
            if (reranked.size() >= topN) {
                break;
            }
        }

        // 结果不足 topN 时，用未命中的候选补齐
        if (reranked.size() < topN) {
            Set<String> picked = new HashSet<>();
            for (RetrievedChunk r : reranked) {
                picked.add(r.getId());
            }
            for (RetrievedChunk c : candidates) {
                if (picked.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("MCD rerank provider config is missing");
        }
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("MCD API密钥缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("MCD rerank model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("MCD rerank 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        JsonObject obj = gson.fromJson(content, JsonObject.class);
        if (obj == null) {
            throw new ModelClientException("MCD rerank 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return obj;
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private ModelClientErrorType classifyStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
