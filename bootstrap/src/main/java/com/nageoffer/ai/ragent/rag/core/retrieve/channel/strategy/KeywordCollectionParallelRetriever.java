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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 关键词（BM25）检索的 Collection 并行检索策略
 * <p>
 * 单个 collection 失败（例如存量 collection 没有 BM25 字段）只记录告警并返回空，不影响其他 collection
 */
@Slf4j
public class KeywordCollectionParallelRetriever extends AbstractParallelRetriever<String> {

    private final RetrieverService retrieverService;

    public KeywordCollectionParallelRetriever(RetrieverService retrieverService, Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, String collectionName, int topK) {
        try {
            return retrieverService.retrieveByKeyword(
                    RetrieveRequest.builder()
                            .collectionName(collectionName)
                            .query(question)
                            .topK(topK)
                            .build()
            );
        } catch (Exception e) {
            log.warn("关键词检索失败，已跳过该 collection（可能未创建 BM25 字段）: {}, 错误: {}", collectionName, e.getMessage());
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(String collectionName) {
        return "Collection: " + collectionName;
    }

    @Override
    protected String getStatisticsName() {
        return "关键词检索";
    }
}
