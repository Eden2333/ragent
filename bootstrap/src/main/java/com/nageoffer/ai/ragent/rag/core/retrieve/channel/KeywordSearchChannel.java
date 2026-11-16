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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.KeywordCollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 关键词（BM25）检索通道
 * <p>
 * 与向量通道互补：向量擅长语义相近，BM25 擅长专有名词、编号、错误码等词面精确匹配。
 * 启用后与向量通道并行执行，结果在后置处理阶段通过 RRF 融合。
 * <p>
 * 检索范围：
 * - 存在 KB 意图时，只检索命中意图对应的 collection
 * - 否则检索全部知识库 collection
 */
@Slf4j
@Component
public class KeywordSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KeywordCollectionParallelRetriever parallelRetriever;

    public KeywordSearchChannel(RetrieverService retrieverService,
                                SearchChannelProperties properties,
                                KnowledgeBaseMapper knowledgeBaseMapper,
                                @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parallelRetriever = new KeywordCollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "KeywordBm25Search";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeyword().isEnabled()
                && StrUtil.isNotBlank(context.getMainQuestion());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> collections = resolveCollections(context);
            if (collections.isEmpty()) {
                log.warn("未找到可用于关键词检索的 collection，跳过");
                return emptyResult(startTime);
            }

            int topK = context.getTopK() * Math.max(1, properties.getChannels().getKeyword().getTopKMultiplier());
            List<RetrievedChunk> chunks = parallelRetriever.executeParallelRetrieval(
                    context.getMainQuestion(), collections, topK);

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", chunks.size(), latency);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_BM25)
                    .channelName(getName())
                    .chunks(chunks)
                    .confidence(0.7)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return emptyResult(startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_BM25;
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD_BM25)
                .channelName(getName())
                .chunks(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    private List<String> resolveCollections(SearchContext context) {
        Set<String> collections = new LinkedHashSet<>();

        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        if (CollUtil.isNotEmpty(context.getIntents())) {
            context.getIntents().stream()
                    .flatMap(si -> si.nodeScores().stream())
                    .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                    .filter(ns -> ns.getScore() >= minScore)
                    .map(NodeScore::getNode)
                    .map(node -> node.getCollectionName())
                    .filter(StrUtil::isNotBlank)
                    .forEach(collections::add);
        }
        if (!collections.isEmpty()) {
            return List.copyOf(collections);
        }

        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        for (KnowledgeBaseDO kb : kbList) {
            if (StrUtil.isNotBlank(kb.getCollectionName())) {
                collections.add(kb.getCollectionName());
            }
        }
        return List.copyOf(collections);
    }
}
