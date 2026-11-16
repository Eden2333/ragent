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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路召回 RRF（Reciprocal Rank Fusion）融合处理器
 * <p>
 * 把检索结果按类型分成"向量路"（意图定向 + 全局向量）和"关键词路"（BM25）两路，
 * 每路内部先去重并按各自分数排名，再按公式融合：
 * <pre>
 *     RRF(d) = Σ w_i / (k + rank_i(d))      rank 从 1 开始
 * </pre>
 * 只使用名次不使用原始分数：向量余弦分与 BM25 分量纲不同，且 BM25 分在不同 collection 间不可比。
 * <p>
 * 融合后的 RRF 分写入 {@link RetrievedChunk#getScore()}，并按 topK × candidateMultiplier 截断后交给 Rerank。
 * 没有关键词路结果时不做任何处理，保持原有行为。
 * <p>
 * 注意：order 必须大于去重处理器，因为去重处理器会基于各通道结果重建列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RrfFusionPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "RrfFusion";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getFusion().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        List<RetrievedChunk> keywordChunks = collect(results, true);
        if (keywordChunks.isEmpty()) {
            return chunks;
        }
        List<RetrievedChunk> vectorChunks = collect(results, false);

        SearchChannelProperties.Fusion fusion = properties.getChannels().getFusion();
        int k = Math.max(1, fusion.getRrfK());

        // key -> chunk（保留向量路的文本优先），key -> RRF 分
        Map<String, RetrievedChunk> chunkByKey = new LinkedHashMap<>();
        Map<String, Double> rrfByKey = new LinkedHashMap<>();
        accumulate(vectorChunks, fusion.getVectorWeight(), k, chunkByKey, rrfByKey);
        accumulate(keywordChunks, fusion.getKeywordWeight(), k, chunkByKey, rrfByKey);

        int limit = Math.max(context.getTopK(), 1) * Math.max(1, fusion.getCandidateMultiplier());
        List<RetrievedChunk> fused = rrfByKey.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> {
                    RetrievedChunk src = chunkByKey.get(e.getKey());
                    return new RetrievedChunk(src.getId(), src.getText(), e.getValue().floatValue());
                })
                .toList();

        log.info("RRF 融合完成 - 向量路: {}, 关键词路: {}, 融合去重后: {}, 截断后: {}",
                vectorChunks.size(), keywordChunks.size(), rrfByKey.size(), fused.size());
        return new ArrayList<>(fused);
    }

    /**
     * 汇总一路结果：按 key 去重（保留最高分），按分数降序后逐个累加 RRF 贡献
     */
    private void accumulate(List<RetrievedChunk> routeChunks,
                            double weight,
                            int k,
                            Map<String, RetrievedChunk> chunkByKey,
                            Map<String, Double> rrfByKey) {
        List<RetrievedChunk> ranked = dedupAndSort(routeChunks);
        for (int i = 0; i < ranked.size(); i++) {
            RetrievedChunk chunk = ranked.get(i);
            String key = chunkKey(chunk);
            chunkByKey.putIfAbsent(key, chunk);
            rrfByKey.merge(key, weight / (k + i + 1), Double::sum);
        }
    }

    private List<RetrievedChunk> dedupAndSort(List<RetrievedChunk> routeChunks) {
        Map<String, RetrievedChunk> best = new LinkedHashMap<>();
        for (RetrievedChunk chunk : routeChunks) {
            best.merge(chunkKey(chunk), chunk,
                    (a, b) -> scoreOf(b) > scoreOf(a) ? b : a);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RrfFusionPostProcessor::scoreOf).reversed())
                .toList();
    }

    private List<RetrievedChunk> collect(List<SearchChannelResult> results, boolean keyword) {
        List<RetrievedChunk> out = new ArrayList<>();
        for (SearchChannelResult result : results) {
            boolean isKeyword = result.getChannelType() == SearchChannelType.KEYWORD_BM25;
            if (isKeyword == keyword && result.getChunks() != null) {
                out.addAll(result.getChunks());
            }
        }
        return out;
    }

    private static double scoreOf(RetrievedChunk chunk) {
        return chunk.getScore() == null ? 0.0 : chunk.getScore();
    }

    private String chunkKey(RetrievedChunk chunk) {
        if (chunk.getId() != null && !chunk.getId().isEmpty()) {
            return chunk.getId();
        }
        return String.valueOf(chunk.getText() == null ? 0 : chunk.getText().hashCode());
    }
}
