package com.nageoffer.ai.ragent.rag.core.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService implements VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseMapper kbMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void indexDocumentChunks(String kbId, String docId, List<VectorChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        final int dim = 4096;
        List<float[]> vectors = extractVectors(chunks, dim);

        String tableName = kbDO.getCollectionName();
        String sql = String.format(
            "INSERT INTO %s (doc_id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
            tableName
        );

        int insertCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("kb_id", kbId);
            metadata.put("doc_id", docId);
            metadata.put("chunk_index", chunk.getIndex());
            
            try {
                String metadataJson = objectMapper.writeValueAsString(metadata);
                PGvector pgVector = new PGvector(vectors.get(i));
                
                jdbcTemplate.update(sql, chunk.getChunkId(), content, metadataJson, pgVector);
                insertCount++;
            } catch (JsonProcessingException e) {
                log.error("插入向量数据失败: chunkId={}", chunk.getChunkId(), e);
                throw new ClientException("插入向量数据失败: " + e.getMessage());
            } catch (Exception e) {
                log.error("插入向量数据失败: chunkId={}", chunk.getChunkId(), e);
                throw new ClientException("插入向量数据失败: " + e.getMessage());
            }
        }

        log.info("pgvector chunk 建立/写入向量索引成功, table={}, rows={}", tableName, insertCount);
    }

    @Override
    public void updateChunk(String kbId, String docId, VectorChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        final int dim = 4096;
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.getChunkId() != null ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
        String content = chunk.getContent() == null ? "" : chunk.getContent();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", kbId);
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.getIndex());

        String tableName = kbDO.getCollectionName();
        String sql = String.format(
            "INSERT INTO %s (doc_id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector) " +
            "ON CONFLICT (doc_id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding",
            tableName
        );

        try {
            String metadataJson = objectMapper.writeValueAsString(metadata);
            PGvector pgVector = new PGvector(vector);
            
            jdbcTemplate.update(sql, chunkPk, content, metadataJson, pgVector);
            
            log.info("pgvector 更新 chunk 向量索引成功, table={}, kbId={}, docId={}, chunkId={}",
                    tableName, kbId, docId, chunkPk);
        } catch (JsonProcessingException e) {
            log.error("更新向量数据失败: chunkId={}", chunkPk, e);
            throw new ClientException("更新向量数据失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("更新向量数据失败: chunkId={}", chunkPk, e);
            throw new ClientException("更新向量数据失败: " + e.getMessage());
        }
    }

    private List<float[]> extractVectors(List<VectorChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            float[] vector = extractVector(chunk, expectedDim);
            vectors.add(vector);
        }
        return vectors;
    }

    private float[] extractVector(VectorChunk chunk, int expectedDim) {
        float[] vector = chunk.getEmbedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    @Override
    public void deleteDocumentVectors(String kbId, String docId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        String tableName = kbDO.getCollectionName();
        String sql = String.format(
            "DELETE FROM %s WHERE metadata->>'kb_id' = ? AND metadata->>'doc_id' = ?",
            tableName
        );

        int deleteCount = jdbcTemplate.update(sql, kbId, docId);
        log.info("pgvector 删除指定文档的所有 chunk 向量索引成功, table={}, kbId={}, docId={}, deleteCnt={}",
                tableName, kbId, docId, deleteCount);
    }

    @Override
    public void deleteChunkById(String kbId, String chunkId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        String tableName = kbDO.getCollectionName();
        String sql = String.format("DELETE FROM %s WHERE doc_id = ?", tableName);

        int deleteCount = jdbcTemplate.update(sql, chunkId);
        log.info("pgvector 删除指定 chunk 向量索引成功, table={}, kbId={}, chunkId={}, deleteCnt={}",
                tableName, kbId, chunkId, deleteCount);
    }
}