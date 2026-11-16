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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import io.milvus.common.clientenum.FunctionType;
import com.nageoffer.ai.ragent.framework.exception.kb.VectorCollectionAlreadyExistsException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorStoreAdmin implements VectorStoreAdmin {

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;
    private final SearchChannelProperties searchChannelProperties;

    /**
     * BM25 稀疏向量字段名，由 BM25 Function 根据 content 自动生成
     */
    public static final String KEYWORD_SPARSE_FIELD = "content_sparse";

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String logicalName = spec.getSpaceId().getLogicalName();
        boolean exists = Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(logicalName).build()
        ));
        if (exists) {
            throw new VectorCollectionAlreadyExistsException(logicalName);
        }

        final boolean keywordEnabled = searchChannelProperties.getChannels().getKeyword().isEnabled();
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("doc_id")
                        .dataType(DataType.VarChar)
                        .maxLength(36)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("content")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .enableAnalyzer(keywordEnabled ? Boolean.TRUE : null)
                        .analyzerParams(keywordEnabled ? Map.of("type", "chinese") : null)
                        .build()
        );
        if (keywordEnabled) {
            fieldSchemaList.add(
                    CreateCollectionReq.FieldSchema.builder()
                            .name(KEYWORD_SPARSE_FIELD)
                            .dataType(DataType.SparseFloatVector)
                            .build()
            );
        }

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("metadata")
                        .dataType(DataType.JSON)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("embedding")
                        .dataType(DataType.FloatVector)
                        .dimension(ragDefaultProperties.getDimension())
                        .build()
        );

        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema
                .builder()
                .fieldSchemaList(fieldSchemaList)
                .build();
        if (keywordEnabled) {
            // BM25 Function：写入 content 时由 Milvus 自动生成稀疏向量，应用层无需写入该字段
            collectionSchema.addFunction(CreateCollectionReq.Function.builder()
                    .name("content_bm25_fn")
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of("content"))
                    .outputFieldNames(List.of(KEYWORD_SPARSE_FIELD))
                    .build());
        }

        IndexParam hnswIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .indexName("embedding")
                .extraParams(Map.of(
                        "M", "48",
                        "efConstruction", "200",
                        "mmap.enabled", "false"
                ))
                .build();

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(hnswIndex);
        if (keywordEnabled) {
            indexParams.add(IndexParam.builder()
                    .fieldName(KEYWORD_SPARSE_FIELD)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .indexName(KEYWORD_SPARSE_FIELD)
                    .build());
        }

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(logicalName)
                .collectionSchema(collectionSchema)
                .primaryFieldName("doc_id")
                .vectorFieldName("embedding")
                .metricType(ragDefaultProperties.getMetricType())
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(indexParams)
                .description(spec.getRemark())
                .build();

        milvusClient.createCollection(createReq);
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        String logicalName = spaceId.getLogicalName();
        return milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(logicalName).build()
        );
    }

    @Override
    public void deleteVectorSpace(VectorSpaceId spaceId) {
        String logicalName = spaceId.getLogicalName();
        boolean exists = milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(logicalName).build()
        );
        if (exists) {
            milvusClient.dropCollection(
                    io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                            .collectionName(logicalName)
                            .build()
            );
            log.info("成功删除Milvus向量空间，Collection: {}", logicalName);
        }
    }
}
