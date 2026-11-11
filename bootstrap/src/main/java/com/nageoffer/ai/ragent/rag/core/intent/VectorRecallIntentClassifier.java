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
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.IntentVectorRecallProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 向量粗筛 + LLM 精筛的意图分类器
 * <p>
 * 流程：
 * <ol>
 *     <li>粗筛：问题向量与叶子节点向量（description 与每条 example 各一个向量，取最大相似度）做余弦相似度，取 Top-K</li>
 *     <li>补充：与 Top-K 中 KB 节点同名的其他 KB 节点（保证跨系统同名歧义引导可用），以及全部 SYSTEM 节点</li>
 *     <li>精筛：仅把候选节点交给 {@link DefaultIntentClassifier} 做 LLM 打分</li>
 * </ol>
 * 降级：未启用、叶子节点数不超过 Top-K、向量索引未就绪或 Embedding 调用失败时，回退为全量 LLM 打分
 * <p>
 * 节点向量缓存在 JVM 内存中，每次请求比对意图树内容，发生变化时增量重算（仅对新增/变更文本调用 Embedding）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRecallIntentClassifier implements IntentClassifier {

    private final DefaultIntentClassifier defaultIntentClassifier;
    private final EmbeddingService embeddingService;
    private final IntentVectorRecallProperties properties;
    private final AIModelProperties aiModelProperties;

    /**
     * 索引重建锁：同一时刻只允许一个线程重建，其余请求本次直接降级
     */
    private final ReentrantLock rebuildLock = new ReentrantLock();

    /**
     * 当前生效的意图向量索引（不可变对象，整体替换）
     */
    private volatile IntentVectorIndex index;

    /**
     * 应用启动完成后异步预热向量索引，避免首个请求承担构建耗时；失败不影响启动
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!properties.isEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<IntentNode> recallable = recallableNodes(defaultIntentClassifier.listLeafNodes());
                if (recallable.size() <= resolveTopK()) {
                    return;
                }
                IntentVectorIndex built = ensureIndex(recallable, resolveModelId());
                if (built != null) {
                    log.info("意图向量索引预热完成, 节点数: {}, 向量数: {}", built.nodeVectors().size(), built.textVectors().size());
                }
            } catch (Exception e) {
                log.warn("意图向量索引预热失败，将在请求时重试", e);
            }
        });
    }

    @Override
    public List<NodeScore> classifyTargets(String question) {
        if (!properties.isEnabled()) {
            return defaultIntentClassifier.classifyTargets(question);
        }

        List<IntentNode> leaves = defaultIntentClassifier.listLeafNodes();
        List<IntentNode> recallable = recallableNodes(leaves);
        int topK = resolveTopK();
        if (recallable.size() <= topK) {
            // 小树短路：候选本来就不超过 Top-K，粗筛没有意义，保持原有行为
            return defaultIntentClassifier.classifyTargets(question);
        }

        List<IntentNode> candidates;
        try {
            candidates = recallCandidates(question, leaves, recallable, topK);
        } catch (Exception e) {
            log.warn("意图向量粗筛失败，降级为全量 LLM 打分, question: {}", question, e);
            return defaultIntentClassifier.classifyTargets(question);
        }
        if (candidates == null) {
            log.info("意图向量索引重建中，本次降级为全量 LLM 打分, question: {}", question);
            return defaultIntentClassifier.classifyTargets(question);
        }
        return defaultIntentClassifier.classifyAmong(question, candidates);
    }

    /**
     * 向量粗筛，返回交给 LLM 精筛的候选节点；索引正在被其他线程重建时返回 null
     */
    private List<IntentNode> recallCandidates(String question,
                                              List<IntentNode> leaves,
                                              List<IntentNode> recallable,
                                              int topK) {
        long start = System.currentTimeMillis();
        String modelId = resolveModelId();
        IntentVectorIndex current = ensureIndex(recallable, modelId);
        if (current == null) {
            return null;
        }

        float[] queryVector = normalize(toArray(embeddingService.embed(question, modelId)));
        if (queryVector.length != current.dimension()) {
            throw new IllegalStateException("问题向量维度(" + queryVector.length + ")与意图节点向量维度("
                    + current.dimension() + ")不一致, modelId=" + modelId);
        }

        List<NodeSimilarity> ranked = recallable.stream()
                .map(node -> new NodeSimilarity(node, current.maxSimilarity(node.getId(), queryVector)))
                .sorted(Comparator.comparingDouble(NodeSimilarity::similarity).reversed())
                .toList();

        Map<String, IntentNode> selected = new LinkedHashMap<>();
        ranked.stream().limit(topK).forEach(s -> selected.put(s.node().getId(), s.node()));
        int expanded = expandSameNameNodes(ranked, selected, topK);
        // SYSTEM 节点（寒暄、自我介绍等）短句向量不稳定，始终交给 LLM 判断
        leaves.stream()
                .filter(IntentNode::isSystem)
                .forEach(node -> selected.putIfAbsent(node.getId(), node));

        if (log.isDebugEnabled()) {
            log.debug("意图向量粗筛 Top-{}: {}", topK, ranked.stream()
                    .limit(topK)
                    .map(s -> s.node().getId() + "=" + String.format(Locale.ROOT, "%.4f", s.similarity()))
                    .collect(Collectors.joining(", ")));
        }
        log.info("意图向量粗筛完成, 可召回节点数: {}, 候选数: {}(同名扩展: {}), 耗时: {}ms",
                recallable.size(), selected.size(), expanded, System.currentTimeMillis() - start);
        return new ArrayList<>(selected.values());
    }

    /**
     * 将与已选 KB 节点同名（归一化后）的其他 KB 节点补入候选，供跨系统同名歧义引导使用
     * <p>
     * 按相似度从高到低补充，最多补充 topK 个，避免常见名称（如「系统介绍」）导致候选膨胀
     *
     * @return 实际补充的节点数
     */
    private int expandSameNameNodes(List<NodeSimilarity> ranked, Map<String, IntentNode> selected, int topK) {
        Set<String> names = selected.values().stream()
                .filter(IntentNode::isKB)
                .map(node -> normalizeName(node.getName()))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        if (names.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (NodeSimilarity s : ranked) {
            if (added >= topK) {
                break;
            }
            IntentNode node = s.node();
            if (!node.isKB() || selected.containsKey(node.getId())) {
                continue;
            }
            if (names.contains(normalizeName(node.getName()))) {
                selected.put(node.getId(), node);
                added++;
            }
        }
        return added;
    }

    /**
     * 确保索引与当前意图树、模型一致；不一致时增量重建
     *
     * @return 可用索引；若其他线程正在重建则返回 null
     */
    private IntentVectorIndex ensureIndex(List<IntentNode> recallable, String modelId) {
        Map<String, List<String>> nodeTexts = buildNodeTexts(recallable);
        IntentVectorIndex current = index;
        if (current != null && current.matches(modelId, nodeTexts)) {
            return current;
        }
        if (!rebuildLock.tryLock()) {
            return null;
        }
        try {
            current = index;
            if (current != null && current.matches(modelId, nodeTexts)) {
                return current;
            }
            IntentVectorIndex rebuilt = buildIndex(modelId, nodeTexts, current);
            index = rebuilt;
            return rebuilt;
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * 构建索引：复用旧索引中同模型、同文本的向量，仅对新增/变更文本调用 Embedding
     */
    private IntentVectorIndex buildIndex(String modelId,
                                         Map<String, List<String>> nodeTexts,
                                         IntentVectorIndex previous) {
        long start = System.currentTimeMillis();
        Map<String, float[]> reusable = previous != null && previous.modelId().equals(modelId)
                ? previous.textVectors()
                : Map.of();

        Set<String> allTexts = new LinkedHashSet<>();
        nodeTexts.values().forEach(allTexts::addAll);
        List<String> missing = allTexts.stream()
                .filter(text -> !reusable.containsKey(text))
                .toList();

        Map<String, float[]> textVectors = new HashMap<>();
        for (String text : allTexts) {
            float[] vector = reusable.get(text);
            if (vector != null) {
                textVectors.put(text, vector);
            }
        }
        if (!missing.isEmpty()) {
            List<List<Float>> vectors = embeddingService.embedBatch(missing, modelId);
            if (vectors == null || vectors.size() != missing.size()) {
                throw new IllegalStateException("意图节点向量化结果数量不匹配, 期望: " + missing.size()
                        + ", 实际: " + (vectors == null ? 0 : vectors.size()));
            }
            for (int i = 0; i < missing.size(); i++) {
                textVectors.put(missing.get(i), normalize(toArray(vectors.get(i))));
            }
        }

        int dimension = -1;
        for (float[] vector : textVectors.values()) {
            if (dimension < 0) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new IllegalStateException("意图节点向量维度不一致: " + dimension + " vs " + vector.length);
            }
        }

        Map<String, List<float[]>> nodeVectors = new HashMap<>();
        nodeTexts.forEach((nodeId, texts) -> nodeVectors.put(nodeId,
                texts.stream().map(textVectors::get).toList()));

        log.info("意图向量索引已重建, modelId: {}, 节点数: {}, 向量数: {}, 新增向量化: {}, 耗时: {}ms",
                modelId, nodeTexts.size(), textVectors.size(), missing.size(), System.currentTimeMillis() - start);
        return new IntentVectorIndex(
                modelId,
                Collections.unmodifiableMap(nodeTexts),
                Collections.unmodifiableMap(textVectors),
                Collections.unmodifiableMap(nodeVectors),
                Math.max(dimension, 0)
        );
    }

    /**
     * 每个节点的向量化文本：路径+说明 一条，每条示例问题各一条（去重、去空）
     */
    private Map<String, List<String>> buildNodeTexts(List<IntentNode> nodes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (IntentNode node : nodes) {
            Set<String> texts = new LinkedHashSet<>();
            StringBuilder desc = new StringBuilder();
            desc.append("路径：").append(StrUtil.blankToDefault(node.getFullPath(), StrUtil.nullToEmpty(node.getName())));
            if (StrUtil.isNotBlank(node.getDescription())) {
                desc.append("\n说明：").append(node.getDescription().trim());
            }
            texts.add(desc.toString());
            if (CollUtil.isNotEmpty(node.getExamples())) {
                for (String example : node.getExamples()) {
                    if (StrUtil.isNotBlank(example)) {
                        texts.add(example.trim());
                    }
                }
            }
            result.put(node.getId(), List.copyOf(texts));
        }
        return result;
    }

    /**
     * 参与向量粗筛的叶子节点：KB 与 MCP（SYSTEM 节点始终进入候选，不参与粗筛）
     */
    private List<IntentNode> recallableNodes(List<IntentNode> leaves) {
        if (CollUtil.isEmpty(leaves)) {
            return List.of();
        }
        return leaves.stream()
                .filter(node -> !node.isSystem())
                .toList();
    }

    private int resolveTopK() {
        return Math.max(1, properties.getTopK());
    }

    private String resolveModelId() {
        String modelId = StrUtil.blankToDefault(
                properties.getEmbeddingModelId(),
                aiModelProperties.getEmbedding().getDefaultModel()
        );
        if (StrUtil.isBlank(modelId)) {
            throw new IllegalStateException("未配置意图粗筛 Embedding 模型: rag.intent.vector-recall.embedding-model-id 与 ai.embedding.default-model 均为空");
        }
        return modelId;
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    private static float[] toArray(List<Float> list) {
        if (list == null) {
            return new float[0];
        }
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Float v = list.get(i);
            arr[i] = v == null ? 0F : v;
        }
        return arr;
    }

    /**
     * L2 归一化，归一化后余弦相似度等于点积；零向量原样返回（相似度恒为 0）
     */
    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum == 0) {
            return vector;
        }
        double norm = Math.sqrt(sum);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    private record NodeSimilarity(IntentNode node, double similarity) {
    }

    /**
     * 意图向量索引（构建后不再修改）
     *
     * @param modelId     向量化所用模型
     * @param nodeTexts   节点ID -> 向量化文本，用于判断意图树是否变化
     * @param textVectors 文本 -> 归一化向量，用于增量重建时复用
     * @param nodeVectors 节点ID -> 该节点所有归一化向量
     * @param dimension   向量维度
     */
    private record IntentVectorIndex(
            String modelId,
            Map<String, List<String>> nodeTexts,
            Map<String, float[]> textVectors,
            Map<String, List<float[]>> nodeVectors,
            int dimension
    ) {

        boolean matches(String modelId, Map<String, List<String>> nodeTexts) {
            return this.modelId.equals(modelId) && this.nodeTexts.equals(nodeTexts);
        }

        /**
         * 节点得分 = 问题向量与该节点所有向量的最大余弦相似度
         */
        double maxSimilarity(String nodeId, float[] normalizedQuery) {
            List<float[]> vectors = nodeVectors.get(nodeId);
            if (vectors == null || vectors.isEmpty()) {
                return 0D;
            }
            double best = -1D;
            for (float[] vector : vectors) {
                double dot = 0D;
                for (int i = 0; i < vector.length; i++) {
                    dot += vector[i] * normalizedQuery[i];
                }
                best = Math.max(best, dot);
            }
            return best;
        }
    }
}
