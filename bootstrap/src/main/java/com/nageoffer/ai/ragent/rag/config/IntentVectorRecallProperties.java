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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图识别向量粗筛配置
 * <p>
 * 流程：问题向量与意图节点向量做余弦相似度粗筛，取 Top-K 候选，再交给 LLM 打分精筛
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent.vector-recall")
public class IntentVectorRecallProperties {

    /**
     * 是否启用向量粗筛
     * 关闭后回退为全量叶子节点交给 LLM 打分（原有逻辑）
     */
    private boolean enabled = true;

    /**
     * 粗筛保留的候选节点数（不含同名扩展节点与 SYSTEM 节点）
     * 可参与粗筛的叶子节点数不超过该值时，跳过粗筛直接走全量 LLM 打分
     */
    private int topK = 10;

    /**
     * 粗筛使用的 Embedding 模型 ID（对应 ai.embedding.candidates[].id）
     * 节点向量与问题向量必须来自同一模型，因此不走模型自动降级；
     * 为空时取 ai.embedding.default-model
     */
    private String embeddingModelId;
}
