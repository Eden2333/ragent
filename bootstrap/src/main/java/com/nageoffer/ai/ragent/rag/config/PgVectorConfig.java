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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PgVector 配置类
 *
 * <p>
 * 自动初始化 pgvector 扩展和向量表结构
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.datasource", name = "driver-class-name", havingValue = "org.postgresql.Driver")
public class PgVectorConfig {

    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void initPgVector() {
        try {
            // 创建 pgvector 扩展
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            log.info("pgvector extension initialized");

            // 创建向量表
            String tableName = ragDefaultProperties.getTableName();
            Integer dimension = ragDefaultProperties.getDimension();
            
            String createTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "  doc_id VARCHAR(36) PRIMARY KEY," +
                "  content TEXT NOT NULL," +
                "  metadata JSONB," +
                "  embedding vector(%d) NOT NULL" +
                ")", tableName, dimension
            );
            jdbcTemplate.execute(createTableSql);
            
            // 创建向量索引（HNSW，最大支持 2000 维）
            if (dimension <= 2000) {
                String indexName = tableName + "_embedding_idx";
                String createIndexSql = String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON %s USING hnsw (embedding vector_cosine_ops)",
                    indexName, tableName
                );
                jdbcTemplate.execute(createIndexSql);
                log.info("pgvector HNSW index created for table '{}'", tableName);
            } else {
                log.warn("pgvector HNSW index not created: dimension {} exceeds 2000 limit. Using exact search.", dimension);
            }
            
            log.info("pgvector table '{}' and index initialized with dimension {}", tableName, dimension);
        } catch (Exception e) {
            log.error("Failed to initialize pgvector", e);
            throw new RuntimeException("pgvector initialization failed", e);
        }
    }
}
