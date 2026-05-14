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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意图节点向量索引管理器
 * <p>
 * 负责将意图节点的语义信息（description + examples）向量化后存入 Milvus，
 * 并提供基于向量相似度的意图检索能力。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentEmbeddingIndexer {

    private static final String COLLECTION_NAME = "intent_embedding_index";
    private static final int TOP_K = 5;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 全量重建意图向量索引
     * <p>
     * 流程：删除旧 collection → 创建新 collection → 向量化所有叶子节点 → 插入 Milvus
     * </p>
     *
     * @param leafNodes 所有叶子意图节点
     */
    public void rebuildIndex(List<IntentNode> leafNodes) {
        if (CollUtil.isEmpty(leafNodes)) {
            log.warn("意图叶子节点为空，跳过索引重建");
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("开始重建意图向量索引，叶子节点数: {}", leafNodes.size());

        // 1. 删除旧 collection
        dropCollectionIfExists();

        // 2. 创建新 collection
        createCollection();

        // 3. 构建语义文本并批量向量化
        List<String> semanticTexts = leafNodes.stream()
                .map(this::buildSemanticText)
                .collect(Collectors.toList());

        List<List<Float>> embeddings = embeddingService.embedBatch(semanticTexts);

        // 4. 插入 Milvus
        List<JsonObject> rows = new ArrayList<>(leafNodes.size());
        for (int i = 0; i < leafNodes.size(); i++) {
            IntentNode node = leafNodes.get(i);
            List<Float> vector = embeddings.get(i);

            JsonObject row = new JsonObject();
            row.addProperty("intent_id", node.getId());
            row.addProperty("content", semanticTexts.get(i));

            // 将 List<Float> 转为 JsonArray
            com.google.gson.JsonArray embArr = new com.google.gson.JsonArray();
            for (Float v : vector) {
                embArr.add(v);
            }
            row.add("embedding", embArr);

            rows.add(row);
        }

        milvusClient.insert(InsertReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(rows)
                .build());

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("意图向量索引重建完成，节点数: {}, 耗时: {}ms", leafNodes.size(), elapsed);
    }

    /**
     * 基于向量相似度检索最匹配的意图节点
     *
     * @param question 用户问题
     * @param topK     返回的最大结果数
     * @return 意图ID和对应的相似度分数列表
     */
    public List<IntentSearchResult> search(String question, int topK) {
        if (!collectionExists()) {
            log.warn("意图向量索引不存在，返回空结果");
            return Collections.emptyList();
        }

        List<Float> queryVector = embeddingService.embed(question);

        SearchResp searchResp = milvusClient.search(SearchReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(Collections.singletonList(new io.milvus.v2.service.vector.request.data.FloatVec(queryVector)))
                .topK(topK > 0 ? topK : TOP_K)
                .outputFields(List.of("intent_id", "content"))
                .build());

        List<List<SearchResp.SearchResult>> results = searchResp.getSearchResults();
        if (CollUtil.isEmpty(results) || CollUtil.isEmpty(results.get(0))) {
            return Collections.emptyList();
        }

        List<IntentSearchResult> searchResults = new ArrayList<>();
        for (SearchResp.SearchResult result : results.get(0)) {
            String intentId = (String) result.getEntity().get("intent_id");
            float score = result.getScore();
            searchResults.add(new IntentSearchResult(intentId, score));
        }

        return searchResults;
    }

    /**
     * 检查索引是否存在
     */
    public boolean collectionExists() {
        return milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(COLLECTION_NAME).build()
        );
    }

    /**
     * 构建意图节点的语义文本
     * <p>
     * 将节点的 fullPath、description、examples 拼接为一段语义丰富的文本，
     * 用于 embedding 向量化。
     * </p>
     */
    private String buildSemanticText(IntentNode node) {
        StringBuilder sb = new StringBuilder();

        // 路径提供层级上下文
        if (node.getFullPath() != null && !node.getFullPath().isEmpty()) {
            sb.append(node.getFullPath()).append("。");
        }

        // 描述是核心语义
        if (node.getDescription() != null && !node.getDescription().isEmpty()) {
            sb.append(node.getDescription()).append("。");
        }

        // 示例问题增强语义覆盖
        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("典型问题：").append(String.join("；", node.getExamples()));
        }

        return sb.toString();
    }

    private void dropCollectionIfExists() {
        if (collectionExists()) {
            milvusClient.dropCollection(
                    DropCollectionReq.builder().collectionName(COLLECTION_NAME).build()
            );
            log.debug("已删除旧的意图向量索引 collection: {}", COLLECTION_NAME);
        }
    }

    private void createCollection() {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();

        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name("intent_id")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name("content")
                .dataType(DataType.VarChar)
                .maxLength(2048)
                .build());

        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name("embedding")
                .dataType(DataType.FloatVector)
                .dimension(ragDefaultProperties.getDimension())
                .build());

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(fields)
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", "16", "efConstruction", "128"))
                .build();

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .description("意图节点向量索引")
                .build());

        log.debug("已创建意图向量索引 collection: {}", COLLECTION_NAME);
    }

    /**
     * 意图向量检索结果
     */
    public record IntentSearchResult(String intentId, float score) {
    }
}
