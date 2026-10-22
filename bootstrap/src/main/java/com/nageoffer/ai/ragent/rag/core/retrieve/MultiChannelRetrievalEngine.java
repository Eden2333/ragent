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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final SearchChannelProperties searchChannelProperties;
    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final Executor ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     *
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        // 构建检索上下文
        SearchContext context = buildSearchContext(subIntents, topK);

        // 灰区场景：采用“先定向 + 按需全量”策略
        if (isGrayZone(context)) {
            return retrieveInGrayZone(context);
        }

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        // 【阶段2：后置处理器链】
        return executePostProcessors(channelResults, context);
    }

    /**
     * 判断当前检索是否处于灰区
     * <p>
     * 灰区定义：意图最高分落在 [lowerBound, upperBound) 区间，
     * 且灰区策略已启用、全局检索通道本身也已启用（否则无需按需补充全量）
     */
    private boolean isGrayZone(SearchContext context) {
        SearchChannelProperties.VectorGlobal vectorGlobal =
                searchChannelProperties.getChannels().getVectorGlobal();
        SearchChannelProperties.GrayZone grayZone = vectorGlobal.getGrayZone();

        if (!grayZone.isEnabled() || !vectorGlobal.isEnabled()) {
            return false;
        }

        if (CollUtil.isEmpty(context.getIntents())) {
            return false;
        }

        double maxScore = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        return maxScore >= grayZone.getLowerBound() && maxScore < grayZone.getUpperBound();
    }

    /**
     * 灰区检索：先定向 + 按需全量
     * <p>
     * 1. 先只执行定向检索通道并走完后置处理链（含 Rerank）；
     * 2. 若 Rerank 后结果数量不足（&lt; topK）或最高分低于质量阈值，
     * 判定召回质量不高，再同步补充执行全局检索通道；
     * 3. 将定向与全局结果合并后重新走一次后置处理链，得到最终结果。
     */
    private List<RetrievedChunk> retrieveInGrayZone(SearchContext context) {
        log.info("进入灰区检索流程：先定向召回，按需补充全量");

        // 【步骤1：仅执行定向检索通道】
        SearchChannel directedChannel = findChannel(SearchChannelType.INTENT_DIRECTED);
        if (directedChannel == null || !directedChannel.isEnabled(context)) {
            log.warn("灰区检索未找到可用的定向检索通道，回退为全局检索");
            SearchChannel globalChannel = findChannel(SearchChannelType.VECTOR_GLOBAL);
            if (globalChannel == null) {
                return List.of();
            }
            return executePostProcessors(List.of(globalChannel.search(context)), context);
        }

        SearchChannelResult directedResult = safeSearch(directedChannel, context);
        List<SearchChannelResult> results = new ArrayList<>();
        results.add(directedResult);

        List<RetrievedChunk> directedFinal = executePostProcessors(results, context);

        // 【步骤2：评估召回质量，判断是否需要补充全量】
        if (!needGlobalFallback(directedFinal, context)) {
            log.info("灰区定向召回质量达标，无需补充全量检索，最终 {} 个 Chunk", directedFinal.size());
            return directedFinal;
        }

        // 【步骤3：按需补充全局检索并合并重排】
        SearchChannel globalChannel = findChannel(SearchChannelType.VECTOR_GLOBAL);
        if (globalChannel == null) {
            log.warn("灰区需要补充全量检索，但未找到全局检索通道，返回定向结果");
            return directedFinal;
        }

        log.info("灰区定向召回不足，补充执行全局检索通道");
        SearchChannelResult globalResult = safeSearch(globalChannel, context);
        results.add(globalResult);

        List<RetrievedChunk> merged = executePostProcessors(results, context);
        log.info("灰区补充全量检索完成，合并重排后最终 {} 个 Chunk", merged.size());
        return merged;
    }

    /**
     * 判断灰区定向召回结果是否需要补充全量检索
     * <p>
     * 触发条件（满足其一）：
     * 1. 结果数量不足 topK；
     * 2. Rerank 后最高分低于质量阈值。
     */
    private boolean needGlobalFallback(List<RetrievedChunk> chunks, SearchContext context) {
        SearchChannelProperties.GrayZone grayZone =
                searchChannelProperties.getChannels().getVectorGlobal().getGrayZone();

        if (CollUtil.isEmpty(chunks)) {
            log.info("灰区定向召回为空，触发全量补充");
            return true;
        }

        if (chunks.size() < context.getTopK()) {
            log.info("灰区定向召回数量不足（{} < topK {}），触发全量补充", chunks.size(), context.getTopK());
            return true;
        }

        double topScore = chunks.stream()
                .map(RetrievedChunk::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Float::doubleValue)
                .max()
                .orElse(0.0);

        if (topScore < grayZone.getRerankScoreThreshold()) {
            log.info("灰区定向召回质量不高（Rerank 最高分 {} < 阈值 {}），触发全量补充",
                    topScore, grayZone.getRerankScoreThreshold());
            return true;
        }

        return false;
    }

    /**
     * 按通道类型查找通道
     */
    private SearchChannel findChannel(SearchChannelType type) {
        return searchChannels.stream()
                .filter(channel -> channel.getType() == type)
                .findFirst()
                .orElse(null);
    }

    /**
     * 安全执行单个通道检索，异常时返回空结果
     */
    private SearchChannelResult safeSearch(SearchChannel channel, SearchContext context) {
        try {
            log.info("执行检索通道：{}", channel.getName());
            return channel.search(context);
        } catch (Exception e) {
            log.error("检索通道 {} 执行失败", channel.getName(), e);
            return SearchChannelResult.builder()
                    .channelType(channel.getType())
                    .channelName(channel.getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .build();
        }
    }

    /**
     * 执行所有启用的检索通道
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 过滤启用的通道
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        // 并行执行所有通道
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return SearchChannelResult.builder()
                                        .channelType(channel.getType())
                                        .channelName(channel.getName())
                                        .chunks(List.of())
                                        .confidence(0.0)
                                        .build();
                            }
                        },
                        ragRetrievalExecutor
                ))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (Exception e) {
                        log.error("获取通道检索结果失败", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    /**
     * 执行后置处理器链
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并排序
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        // 初始 Chunk 列表（所有通道的结果合并）
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // 依次执行处理器
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    /**
     * 构建检索上下文
     */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .topK(topK)
                .build();
    }
}
