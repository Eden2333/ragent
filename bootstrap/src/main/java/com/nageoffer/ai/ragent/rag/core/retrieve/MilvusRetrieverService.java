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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService implements RetrieverService {

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> emb = embeddingService.embed(retrieveParam.getQuery());
        float[] vec = toArray(emb);
        float[] norm = normalize(vec);
        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String tableName = StrUtil.isBlank(retrieveParam.getCollectionName()) 
                ? ragDefaultProperties.getTableName() 
                : retrieveParam.getCollectionName();
        
        String sql = String.format(
            "SELECT doc_id, content, 1 - (embedding <=> ?::vector) AS score " +
            "FROM %s " +
            "ORDER BY embedding <=> ?::vector " +
            "LIMIT ?",
            tableName
        );
        
        PGvector pgVector = new PGvector(vector);
        
        List<RetrievedChunk> results = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> RetrievedChunk.builder()
                .id(rs.getString("doc_id"))
                .text(rs.getString("content"))
                .score(rs.getFloat("score"))
                .build(),
            pgVector,
            pgVector,
            retrieveParam.getTopK()
        );
        
        // TODO 需确认后续是否对分数较低数据进行限制，限制多少合适？0.65？
        // TODO 如果本次查询分数都较高，是否应该扩大查询范围？1.5倍？
        return results;
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
