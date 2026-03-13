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

import com.nageoffer.ai.ragent.framework.exception.kb.VectorCollectionAlreadyExistsException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorStoreAdmin implements VectorStoreAdmin {

    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String tableName = spec.getSpaceId().getLogicalName();
        
        if (vectorSpaceExists(spec.getSpaceId())) {
            throw new VectorCollectionAlreadyExistsException(tableName);
        }

        String createTableSql = String.format(
            "CREATE TABLE %s (" +
            "  doc_id VARCHAR(36) PRIMARY KEY," +
            "  content TEXT," +
            "  metadata JSONB," +
            "  embedding vector(%d)" +
            ")",
            tableName,
            ragDefaultProperties.getDimension()
        );
        
        jdbcTemplate.execute(createTableSql);
        
        // pgvector HNSW 索引支持最大 2000 维
        if (ragDefaultProperties.getDimension() <= 2000) {
            String createIndexSql = String.format(
                "CREATE INDEX ON %s USING hnsw (embedding vector_cosine_ops)",
                tableName
            );
            jdbcTemplate.execute(createIndexSql);
            log.info("Created vector table with HNSW index: {} with dimension: {}", tableName, ragDefaultProperties.getDimension());
        } else {
            log.warn("Created vector table without index: {} with dimension: {} (exceeds 2000 limit for HNSW)", 
                tableName, ragDefaultProperties.getDimension());
        }
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        String tableName = spaceId.getLogicalName();
        String checkSql = "SELECT EXISTS (" +
            "  SELECT FROM information_schema.tables " +
            "  WHERE table_name = ?" +
            ")";
        
        Boolean exists = jdbcTemplate.queryForObject(checkSql, Boolean.class, tableName);
        return Boolean.TRUE.equals(exists);
    }
}
