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

package com.nageoffer.ai.ragent.ingestion.node;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引节点类，负责将处理后的文档分块数据索引到向量数据库中
 * 该类实现了 {@link IngestionNode} 接口，是数据摄入流水线中的关键节点
 * 主要功能包括：解析配置、生成向量嵌入、确保向量空间存在以及将数据批量插入到向量数据库
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       JdbcTemplate jdbcTemplate,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.jdbcTemplate = jdbcTemplate;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());
        String tableName = resolveCollectionName(context);
        if (!StringUtils.hasText(tableName)) {
            return NodeResult.fail(new ClientException("索引器需要指定表名称"));
        }

        int expectedDim = resolveDimension(chunks);
        if (expectedDim <= 0) {
            return NodeResult.fail(new ClientException("未配置向量维度"));
        }
        float[][] vectorArray;
        try {
            vectorArray = toArrayFromChunks(chunks, expectedDim);
        } catch (ClientException ex) {
            return NodeResult.fail(ex);
        }

        ensureVectorSpace(tableName);
        int insertCount = insertRows(context, tableName, chunks, vectorArray, settings.getMetadataFields());
        return NodeResult.ok("已写入 " + insertCount + " 个分块到表 " + tableName);
    }

    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    private String resolveCollectionName(IngestionContext context) {
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getTableName();
    }

    private void ensureVectorSpace(String tableName) {
        boolean vectorSpaceExists = vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                .logicalName(tableName)
                .build());
        if (vectorSpaceExists) {
            return;
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(tableName)
                        .build())
                .remark("RAG向量存储空间")
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);
    }

    private int insertRows(IngestionContext context, String tableName, List<VectorChunk> chunks, 
                          float[][] vectors, List<String> metadataFields) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        
        String sql = String.format(
            "INSERT INTO %s (doc_id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
            tableName
        );
        
        Map<String, Object> mergedMetadata = mergeMetadata(context);
        int insertCount = 0;
        
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String chunkId = StringUtils.hasText(chunk.getChunkId()) ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            chunk.setChunkId(chunkId);
            chunk.setEmbedding(vectors[i]);

            String content = chunk.getContent() == null ? "" : chunk.getContent();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunk_index", chunk.getIndex());
            metadata.put("task_id", context.getTaskId());
            metadata.put("pipeline_id", context.getPipelineId());
            
            DocumentSource source = context.getSource();
            if (source != null && source.getType() != null) {
                metadata.put("source_type", source.getType().getValue());
            }
            if (source != null && StringUtils.hasText(source.getLocation())) {
                metadata.put("source_location", source.getLocation());
            }

            if (metadataFields != null && !metadataFields.isEmpty()) {
                Map<String, Object> combined = new HashMap<>(mergedMetadata);
                if (chunk.getMetadata() != null) {
                    combined.putAll(chunk.getMetadata());
                }
                for (String field : metadataFields) {
                    if (StringUtils.hasText(field)) {
                        Object value = combined.get(field);
                        if (value != null) {
                            metadata.put(field, value);
                        }
                    }
                }
            }
            
            try {
                String metadataJson = objectMapper.writeValueAsString(metadata);
                PGvector pgVector = new PGvector(vectors[i]);
                
                jdbcTemplate.update(sql, chunkId, content, metadataJson, pgVector);
                insertCount++;
            } catch (JsonProcessingException e) {
                log.error("插入向量数据失败: chunkId={}", chunkId, e);
                throw new ClientException("插入向量数据失败: " + e.getMessage());
            } catch (Exception e) {
                log.error("插入向量数据失败: chunkId={}", chunkId, e);
                throw new ClientException("插入向量数据失败: " + e.getMessage());
            }
        }
        
        log.info("pgvector 写入成功，表={}，行数={}", tableName, insertCount);
        return insertCount;
    }

    private int resolveDimension(List<VectorChunk> chunks) {
        Integer configured = ragDefaultProperties.getDimension();
        if (configured != null && configured > 0) {
            return configured;
        }
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                return chunk.getEmbedding().length;
            }
        }
        return 0;
    }

    private float[][] toArrayFromChunks(List<VectorChunk> chunks, int expectedDim) {
        float[][] out = new float[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = chunks.get(i).getEmbedding();
            if (vector == null || vector.length == 0) {
                throw new ClientException("向量结果缺失，索引: " + i);
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new ClientException("向量维度不匹配，索引: " + i);
            }
            out[i] = vector;
        }
        return out;
    }

    private Map<String, Object> mergeMetadata(IngestionContext context) {
        Map<String, Object> merged = new HashMap<>();
        if (context.getMetadata() != null) {
            merged.putAll(context.getMetadata());
        }
        return merged;
    }
}
