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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义向量意图分类器
 * <p>
 * 使用 embedding 向量检索替代 LLM 进行意图识别，大幅提升分类速度（20s → 50ms）。
 * <p>
 * 策略：
 * <ul>
 *   <li>top-1 分数 > 0.85 → 直接使用，跳过 LLM</li>
 *   <li>0.5 ~ 0.85 → 用 LLM 对 top-3 候选做精细判断</li>
 *   <li>< 0.5 → 无匹配意图，走全局检索</li>
 * </ul>
 */
@Slf4j
@Service("semanticIntentClassifier")
@Primary
@RequiredArgsConstructor
public class SemanticIntentClassifier implements IntentClassifier {

    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    private static final int SEARCH_TOP_K = 5;

    private final IntentEmbeddingIndexer intentEmbeddingIndexer;
    private final IntentTreeCacheManager intentTreeCacheManager;

    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier llmClassifier;

    @Override
    public List<NodeScore> classifyTargets(String question) {
        // 如果向量索引不存在，降级到 LLM 分类器
        if (!intentEmbeddingIndexer.collectionExists()) {
            log.info("意图向量索引不存在，降级到 LLM 分类器");
            return llmClassifier.classifyTargets(question);
        }

        long startTime = System.currentTimeMillis();

        // 1. 向量检索
        List<IntentEmbeddingIndexer.IntentSearchResult> searchResults =
                intentEmbeddingIndexer.search(question, SEARCH_TOP_K);

        if (CollUtil.isEmpty(searchResults)) {
            log.info("[Semantic] 问题: {} | 向量检索无结果, 耗时: {}ms", question, System.currentTimeMillis() - startTime);
            return List.of();
        }

        // 2. 加载意图树，构建 id -> node 映射
        Map<String, IntentNode> id2Node = loadId2NodeMap();
        if (id2Node.isEmpty()) {
            log.warn("[Semantic] 意图树为空，降级到 LLM 分类器");
            return llmClassifier.classifyTargets(question);
        }

        // 3. 将检索结果映射为 NodeScore
        List<NodeScore> candidates = new ArrayList<>();
        for (IntentEmbeddingIndexer.IntentSearchResult result : searchResults) {
            IntentNode node = id2Node.get(result.intentId());
            if (node != null) {
                candidates.add(new NodeScore(node, result.score()));
            }
        }

        if (candidates.isEmpty()) {
            log.info("[Semantic] 问题: {} | 检索到的意图ID无法匹配节点, 耗时: {}ms",
                    question, System.currentTimeMillis() - startTime);
            return List.of();
        }

        // 按分数降序排序
        candidates.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

        double topScore = candidates.get(0).getScore();
        long elapsed = System.currentTimeMillis() - startTime;

        // 4. 根据阈值决定策略
        if (topScore >= HIGH_CONFIDENCE_THRESHOLD) {
            // 高置信度：直接使用向量检索结果
            List<NodeScore> result = candidates.stream()
                    .filter(ns -> ns.getScore() >= LOW_CONFIDENCE_THRESHOLD)
                    .limit(3)
                    .toList();
            log.info("[Semantic] 问题: {} | 高置信度命中, top1={}, 结果数: {}, 耗时: {}ms",
                    question, String.format("%.3f", topScore), result.size(), elapsed);
            return result;
        } else if (topScore >= LOW_CONFIDENCE_THRESHOLD) {
            // 中等置信度：降级到 LLM 对候选做精细判断
            log.info("[Semantic] 问题: {} | 中等置信度(top1={}), 降级到 LLM 精细判断, 向量检索耗时: {}ms",
                    question, String.format("%.3f", topScore), elapsed);
            return llmClassifier.classifyTargets(question);
        } else {
            // 低置信度：无匹配
            log.info("[Semantic] 问题: {} | 低置信度(top1={}), 无匹配意图, 耗时: {}ms",
                    question, String.format("%.3f", topScore), elapsed);
            return List.of();
        }
    }

    private Map<String, IntentNode> loadId2NodeMap() {
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();
        if (CollUtil.isEmpty(roots)) {
            return Map.of();
        }
        return flatten(roots).stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n, (a, b) -> a));
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        java.util.Deque<IntentNode> stack = new java.util.ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }
}
