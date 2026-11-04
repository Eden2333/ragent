# Ragent RAG 技术成熟度分析报告

> 只读调研，未修改任何代码。所有结论均附文件路径与符号证据；读不到或无法确认的点标注「未验证」。
> 评估基准：2026 年主流 RAG 工程方案。

---

## 一、总结答案（先看结论）

Ragent 是一个**工程化程度很高、但检索内核仍属"增强版朴素 RAG"**的系统。它的工程护城河（多模型路由容错、限流熔断、全链路 Trace、可编排入库流水线、SSE 流式）明显领先于普通 Demo，但**在决定 RAG 效果上限的几个核心技术点上落后于 2026 主流**：

- 检索是**纯向量检索（Milvus HNSW/COSINE）**，没有 BM25/稀疏向量混合检索，没有 RRF 融合。
- **有真实 Rerank**（阿里云百炼 rerank，带路由与降级），这是亮点。
- **有查询改写 + 多子问题拆分 + 术语归一化**，但没有 HyDE / Step-back / 向量层面的 Multi-Query 融合。
- **有意图识别与路由**，用 LLM 结构化输出对树形意图节点打分，设计较完整。
- **分块策略有 4 种**（固定/结构感知Markdown/句子/段落），**没有父子分块 Small-to-Big、没有语义分块、没有 Late Chunking**。
- 整体是**固定管道（朴素 RAG 的增强版）**，**不是 Agentic RAG**：无 Self-RAG/CRAG、无检索质量反思、无迭代重检索、无纠错循环、无迭代上限概念。MCP 工具调用由 LLM 意图打分静态路由，不是自主 Agent 循环。
- **不支持多模态、不支持 GraphRAG**。
- **没有使用 Spring AI / LangChain4j**，是完全自研的 `infra-ai` 抽象层。

一句话：**README 自称"Agentic RAG"与代码实际形态不符**，当前实现更准确的定位是"带意图路由和工具调用分支的、流程固定的多路向量 RAG"。

> ⚠️ 关于用户第 3 问的直接回答：**是的**。朴素 RAG 流程固定，Agentic RAG 由 LLM 自主决策流程。Ragent 的主流程（见下）是**固定管道**——每一步都会执行，分支（是否走 MCP、是否走全局兜底）由阈值和意图打分决定，但**没有 LLM 自主规划"下一步做什么"、也不会根据检索结果好坏回头重做**，所以它不满足 Agentic RAG 的核心特征。

---

## 二、主流程证据（固定管道）

**文件**：`bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` → `streamChat()`

该方法的类注释明确写出流程：
> 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出

代码按固定顺序串行执行：
1. `memoryService.loadAndAppend(...)` 加载会话记忆
2. `queryRewriteService.rewriteWithSplit(question, history)` 改写+拆分
3. `intentResolver.resolve(rewriteResult)` 对每个子问题 LLM 打分
4. `guidanceService.detectAmbiguity(...)` 歧义引导（置信度接近则反问澄清）
5. 若全部意图为 `SYSTEM` → 直接 LLM 回答（跳过检索）
6. `retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K)` 检索
7. `ctx.isEmpty()` → 返回"未检索到相关内容"
8. `streamLLMResponse(...)` 生成答案

**关键观察**：第 7 步检索为空就直接放弃；**没有"换个查询再试""降低阈值重检索""质量不够重新规划"的回路**。这是典型固定管道（朴素 RAG）而非 Agentic RAG。

---

## 三、逐条结论（对应任务 8 问）

### 1. 检索方案 / 向量库

**结论：纯向量检索，无混合检索；向量库是 Milvus 2.6。落后。**

证据：
- 向量库 Milvus：`pom.xml` `milvus-sdk.version=2.6.6`；`io.milvus.v2.client.MilvusClientV2`。
- Collection Schema 只有 4 个字段，向量字段 `embedding` 为 `FloatVector`，索引 `HNSW` + `COSINE`，**无稀疏向量/BM25 字段**：
  `rag/core/vector/MilvusVectorStoreAdmin.java` → `ensureVectorSpace()`（字段：`doc_id` / `content` / `metadata`(JSON) / `embedding`(FloatVector)；`IndexParam.IndexType.HNSW`）。
- 检索只走向量 ANN：`rag/core/retrieve/MilvusRetrieverService.java` → `retrieveByVector()`，`annsField("embedding")`，输出 `doc_id/content/metadata`。无全文/关键词检索分支。
- 两个检索通道都是向量检索，区别只是"检索哪些 collection"：
  - `IntentDirectedSearchChannel`（按意图命中的 KB 定向检索，优先级最高）
  - `VectorGlobalSearchChannel`（意图缺失或置信度低时全库兜底）
- ES 关键词通道、混合检索在文档中被明确列为**未来扩展**，尚未实现：`docs/multi-channel-retrieval.md`（流程图中 `KeywordESSearchChannel（ES 关键词检索，未来扩展）`，"未来扩展"章节第 1 条）。`SearchChannelType` 中 `KEYWORD_ES`/`HYBRID` 为预留枚举，无实现类（证据：`core/retrieve/channel` 目录下只有 `IntentDirectedSearchChannel`、`VectorGlobalSearchChannel` 两个实现）。

> ⚠️避坑：README「多路检索」容易被理解成"向量+关键词混合"，实际是"向量检索的两条路由通道（定向 vs 全局）"，两条都是纯向量，没有召回模态上的互补。精确匹配类查询（订单号、错误码、专有名词）召回弱是这种架构的典型短板。

### 2. Rerank 重排

**结论：有，真实的 Cross-Encoder 式精排（阿里云百炼 rerank），带模型路由与降级。持平/局部领先。**

证据：
- 后处理器链最后一步始终执行 Rerank：`rag/core/retrieve/postprocessor/RerankPostProcessor.java`（`getOrder()=10`，`isEnabled()` 恒为 `true`，调用 `rerankService.rerank(mainQuestion, chunks, topK)`）。
- 精排服务接口与路由实现：`infra-ai/.../infra/rerank/RerankService.java`、`RoutingRerankService.java`（`@Primary`，按 provider 选择 `RerankClient`，走 `ModelRoutingExecutor.executeWithFallback(ModelCapability.RERANK, ...)` 带降级）。
- 真实供应商实现：`infra-ai/.../infra/rerank/BaiLianRerankClient.java`（调用百炼 rerank HTTP API，解析 `output.results`）。
- 兜底空实现：`NoopRerankClient.java`（无可用模型时不排序直接返回）。
- 候选选择：`infra/model/ModelSelector.selectRerankCandidates()`，配置项 `AIModelProperties.rerank`。

> 说明：这是当前实现里最接近主流的一环。唯一限制是**精排仅依赖云端 rerank API**，没有本地 bge-reranker 选项（供应商抽象层允许扩展，但目前只有百炼实现）。

### 3. 查询改写 / 扩展

**结论：有改写 + 多子问题拆分 + 术语归一化；无 HyDE / Step-back / 向量级 Multi-Query 融合。持平偏下。**

证据：
- 改写+拆分：`rag/core/rewrite/MultiQuestionRewriteService.java` → `rewriteWithSplit(question, history)`，LLM 输出 `{rewrite, sub_questions}` JSON，带开关 `ragConfigProperties.getQueryRewriteEnabled()` 与规则兜底 `ruleBasedSplit()`。改写时携带最近 2 轮历史做上下文补全（多轮指代消解）。
- 术语归一化（领域同义词替换）：`rag/core/rewrite/QueryTermMappingService.normalize()`，规则来自 DB 表 `QueryTermMappingDO`，按优先级+词长排序替换。
- Multi-Query 的处理方式是：拆成多个子问题 → 每个子问题各自意图识别+检索 → 结果合并（`IntentResolver.resolve()` 并发、`RetrievalEngine.retrieve()` 合并）。属于"问题级多路"，**不是 HyDE（假设性文档嵌入）、也不是 Step-back（抽象问题回退）**。
- **未验证/未实现**：全仓 grep `HyDE`、`step-back` 无命中（见证据搜索）。

### 4. 意图识别 / 路由

**结论：有，LLM 结构化输出对树形意图节点打分，设计完整。持平（相对主流做法属常规偏上）。**

证据：
- 分类器：`rag/core/intent/DefaultIntentClassifier.java` → `classifyTargets(question)`。把所有**叶子意图节点**（id/path/description/examples/type）拼进 Prompt，让 LLM 输出 `[{id, score, reason}]`，再映射回节点并按分降序。`temperature=0.1`。
- 意图树：来自 DB 表 `IntentNodeDO`，父子关系在内存组装，Redis 缓存（`IntentTreeCacheManager`）。节点有三类 `IntentKind`：`KB`（走知识库）、`MCP`（走工具）、`SYSTEM`（直接聊天不检索）——`rag/enums/IntentKind.java`。
- 路由决策：
  - 全部 `SYSTEM` → 跳过检索直接回答（`RAGChatServiceImpl` 第 5 步 `allSystemOnly`）。
  - `KB` 意图 → `IntentDirectedSearchChannel` 定向检索。
  - `MCP` 意图 → `RetrievalEngine.executeMcpTools()` 调用工具。
  - 置信度低/无意图 → `VectorGlobalSearchChannel` 全局兜底。
- 歧义引导：`rag/core/guidance/IntentGuidanceService.detectAmbiguity()`，多个意图得分接近时反问用户澄清（`GuidanceDecision.isPrompt()`）。
- 阈值常量：`RAGConstant.INTENT_MIN_SCORE`、`MAX_INTENT_COUNT`；通道阈值 `SearchChannelProperties`（`confidence-threshold`、`min-intent-score`）。

> 实现方式是 **LLM 结构化打分 + 规则阈值路由**，不是 BERT 分类器、也不是纯规则。对于意图节点数量不大的企业场景是合理选择；节点多时单次 Prompt 列全量叶子节点会有 Token 和延迟压力（分类器注释自述"适用于意图数量较少的场景"）。

### 5. 分块策略

**结论：有 4 种基础策略；无父子分块/Small-to-Big、无语义分块、无 Late Chunking。落后。**

证据：
- 策略枚举 `core/chunk/ChunkingMode.java`：`FIXED_SIZE`、`STRUCTURE_AWARE`(Markdown 友好)、`SENTENCE`、`PARAGRAPH`。
- 对应实现在 `core/chunk/strategy/`：`FixedSizeTextChunker`、`StructureAwareTextChunker`、`SentenceChunker`、`ParagraphChunker`。工厂 `ChunkingStrategyFactory`（EnumMap 注册）。
- `StructureAwareTextChunker` 质量不错：按 Markdown 块（标题/段落/代码围栏/图片链接原子行）边界切，min/target/max 字符预算（默认 600/1400/1800），可选 overlap。但仍是**单层平铺 chunk**，检索命中的 chunk 直接用于生成，**没有"检索小块、喂大块给 LLM"的父子/Small-to-Big 机制**。
- 入库富集 `ingestion/node/EnricherNode.java` + `ChunkEnrichType`(KEYWORDS/SUMMARY/METADATA)：用 LLM 为 chunk 生成关键词/摘要/元数据，写入 chunk 的 `metadata`。
  > ⚠️避坑（重要）：这些 keywords/summary 虽然算出来并存进 Milvus 的 `metadata` JSON，但**检索时并不使用它们**——`MilvusRetrieverService` 只对 `embedding` 向量做 ANN，且嵌入的是**原始 chunk 内容**（`AbstractEmbeddingChunker` 对 `chunk.getContent()` 做 embedding），不是"内容+摘要"。也没有基于 keywords 的关键词索引。因此富集结果对召回几乎没有贡献，属于"算了但没用上"的半成品能力。（未验证：是否有其他路径消费 metadata.summary，全仓未见检索侧引用。）
- 语义分块（按向量相似度断句）、Late Chunking：全仓 grep 无命中（见证据搜索）。

### 6. 朴素 RAG vs Agentic RAG / 反思纠错

**结论：固定管道（增强版朴素 RAG）。无 Self-RAG/CRAG、无反思、无迭代重检索、无迭代上限。落后（相对"Agentic RAG"宣称）。**

证据：
- 主流程 `RAGChatServiceImpl.streamChat()` 为单向固定管道（见第二节），检索为空即放弃，无回路。
- 全仓 grep `Self-RAG/CRAG/reflect/反思/纠错/迭代` 在业务代码中**无命中**（命中项均为 `java.lang.reflect` 反射工具，与 RAG 反思无关，见证据搜索）。
- MCP 工具调用（`RetrievalEngine.executeMcpTools()`）是**静态路由**：意图命中 MCP 节点 → 提取参数 → 执行工具 → 结果拼进上下文。LLM 不参与"是否调用/调用后是否继续"的自主决策，没有 ReAct/Plan-Execute 式 Agent 循环，没有工具调用的多轮反馈。
- "深度思考模式"（`deepThinking`）只是把 `thinking=true` 透传给模型（`streamLLMResponse` 中设置 `ChatRequest.thinking(deepThinking)`），是模型侧的思维链开关，**不是检索/决策层的 Agent 迭代**。

> 这是本项目与 README「Agentic RAG」宣称差距最大的一项。要够得上 Agentic RAG，至少需要：检索质量自评（CRAG 的 correct/ambiguous/incorrect 分级）、不足时自主改写并重检索、带迭代上限的 ReAct 工具循环。目前都没有。

### 7. 多模态 / GraphRAG

**结论：均不支持。落后（但对多数企业文本知识库场景非必需）。**

证据：
- GraphRAG：全仓 grep `GraphRAG/graph` 无业务命中（见证据搜索）。无图数据库依赖（`pom.xml` 无 Neo4j 等）。
- 多模态：`RetrieverService.java` 接口注释里"多模态检索"只是**可扩展方向的文字描述**，无实现。图片在 Markdown 分块中作为原子行文本保留（`StructureAwareTextChunker` 的 `ATOMIC_IMAGE`），但不做图像 embedding，不支持图文联合检索。文档解析用 Apache Tika（`pom.xml` tika 3.2.3），面向文本抽取。

### 8. 是否 Spring AI / 用了哪些 Advisor

**结论：没有用 Spring AI，也没有 LangChain4j。完全自研 `infra-ai` 抽象层。无 QuestionAnswerAdvisor 等。**

证据：
- 全仓 grep `org.springframework.ai` / `spring-ai` / `QuestionAnswerAdvisor` / `Advisor` **无任何命中**（见证据搜索）。
- `pom.xml` 无 Spring AI BOM/starter 依赖；AI 能力自建于 `infra-ai` 模块：`infra/chat`（LLMService/ChatClient/StreamCallback）、`infra/embedding`（EmbeddingClient）、`infra/rerank`（RerankClient）、`infra/model`（ModelSelector/ModelRoutingExecutor 路由+熔断降级）。
- 向量库直接用 Milvus Java SDK v2（`MilvusClientV2`），非 Spring AI VectorStore 抽象。

> 自研的代价：QuestionAnswerAdvisor、RetrievalAugmentationAdvisor、各类 QueryTransformer（Spring AI 已内置 HyDE/Multi-Query/Compression/Rewrite 等）这些开箱能力都要自己造。好处是可控、不被 Spring AI 版本迭代绑架（README 也吐槽了版本问题）。

---

## 四、当前实现 vs 2026 主流 对照表

| 维度 | Ragent 当前实现 | 2026 主流方案 | 判定 |
|---|---|---|---|
| 向量库 | Milvus 2.6（HNSW/COSINE） | Milvus/pgvector/Qdrant 等，支持稠密+稀疏 | 持平 |
| 检索模态 | 纯向量 ANN | 向量 + BM25/稀疏 混合检索 + RRF 融合 | **落后** |
| 多路召回 | 向量定向通道 + 向量全局通道 | 向量+关键词+（可选图/SQL）多模态互补 | **落后** |
| Rerank | 云端百炼 rerank，路由+降级 | Cross-Encoder/bge-reranker/Cohere，常本地部署 | 持平 |
| 查询改写 | LLM 改写+拆分+术语归一化+多轮补全 | 改写 + HyDE + Step-back + Multi-Query 融合 | 持平偏下 |
| 意图识别/路由 | LLM 结构化打分 + 树形意图 + 阈值路由 + 歧义引导 | LLM 路由 / 小模型分类，语义路由 | **持平** |
| 分块 | 固定/结构感知/句子/段落 4 种 | + 父子Small-to-Big + 语义分块 + Late Chunking | **落后** |
| 入库富集 | LLM 生成 keywords/summary（但检索未使用） | 富集内容纳入嵌入/关键词索引，真正提升召回 | **落后** |
| 流程范式 | 固定管道（朴素 RAG 增强版） | Agentic：Self-RAG/CRAG、反思、迭代重检索 | **落后** |
| 工具调用 | 意图静态路由 MCP，单次执行 | ReAct/Plan-Execute Agent 循环，多轮反馈 | **落后** |
| 多模态 | 不支持 | 图文/表格联合检索（部分场景） | 落后（非必需） |
| GraphRAG | 不支持 | 关系密集场景采用 | 落后（非必需） |
| 会话记忆 | 滑动窗口 + 自动摘要压缩 | 同类 + 向量记忆/实体记忆 | 持平 |
| 模型工程 | 多候选路由+首包探测+三态熔断降级 | 同类网关能力 | **领先**（相对多数 RAG 项目） |
| 可观测性 | AOP 全链路 Trace（@RagTraceNode） | OpenTelemetry/评测体系 | 持平（缺自动化评测） |
| 框架 | 自研 infra-ai | Spring AI / LangChain4j | 中性（取舍） |

---

## 五、改进建议（按投入产出比排序）

> 排序遵循任务要求：混合检索+Rerank → 查询改写 → 意图识别 → Agentic RAG。Rerank 已具备，故混合检索为第一优先。

**P0 — 混合检索（最高 ROI，补最硬的短板）**
- 给 Milvus Collection 增加稀疏向量字段（Milvus 2.6 原生支持 `SparseFloatVector` + BM25/`BM25` function），或新增一条真正的关键词/ES 通道，与现有向量通道并行。
- 用 RRF（Reciprocal Rank Fusion）融合多路结果，再送现有 `RerankPostProcessor` 精排。
- 改动集中在 `MilvusVectorStoreAdmin`(schema+索引)、`MilvusRetrieverService`(混合检索请求)、新增 `HybridSearchChannel` + `RrfFusionPostProcessor`。扩展点已就绪（`SearchChannel`/`SearchResultPostProcessor` 接口 + 文档已把 ES 通道列为规划）。
- 顺带修复「富集未被使用」问题：把 keywords/summary 纳入关键词索引或拼入嵌入文本，让入库富集真正产生召回收益。
- 预期收益：精确匹配类查询（专名/编号/错误码）召回大幅提升，这是纯向量最大痛点。

**P1 — 查询改写增强**
- 在现有 `MultiQuestionRewriteService` 基础上加 HyDE（生成假设答案再嵌入检索）与 Step-back（抽象问题回退检索），对宽泛/模糊问题提升召回。
- 复用现有 LLM 调用与子问题合并框架，改动面小。

**P2 — 意图识别优化**
- 当前每次把全量叶子节点塞进 Prompt，节点规模增大后 Token/延迟上升。可引入"先向量粗筛候选意图、再 LLM 精判"的两阶段，或对高频意图做小模型/缓存短路。
- 低风险增量优化，收益随意图规模增长而放大。

**P3 — 向 Agentic RAG 演进（最高复杂度，谨慎推进）**
- 最小可行：引入 CRAG 式"检索质量自评 → 不足则改写重检索（带迭代上限 2~3 次）"，挂在 `RetrievalEngine.retrieve()` 之后、生成之前。
- 进阶：把 MCP 工具调用改成带反馈的 ReAct 循环（LLM 决定是否继续调用/检索）。
- 这是让项目名副其实"Agentic"的关键，但对延迟、成本、稳定性影响大，建议作为可开关的高级模式，不默认开启。

**附：文档一致性建议（非代码）**
- README 多处宣称"Agentic RAG"，与当前固定管道实现不符，建议要么补齐 P3 能力，要么在文档中把定位修正为"多路向量 RAG + 意图路由 + 工具调用"，避免面试/使用预期落差。

---

## 六、证据搜索汇总（可复核）

- 主流程：`rag/service/impl/RAGChatServiceImpl.java#streamChat`
- 检索编排：`rag/core/retrieve/RetrievalEngine.java`、`MultiChannelRetrievalEngine.java`
- 向量检索：`rag/core/retrieve/MilvusRetrieverService.java#retrieveByVector`
- 向量 schema（证明无稀疏字段）：`rag/core/vector/MilvusVectorStoreAdmin.java#ensureVectorSpace`
- 检索通道：`rag/core/retrieve/channel/IntentDirectedSearchChannel.java`、`VectorGlobalSearchChannel.java`
- Rerank：`rag/core/retrieve/postprocessor/RerankPostProcessor.java`；`infra-ai/.../infra/rerank/{RerankService,RoutingRerankService,BaiLianRerankClient,NoopRerankClient}.java`
- 改写/拆分/术语：`rag/core/rewrite/{MultiQuestionRewriteService,QueryTermMappingService}.java`
- 意图：`rag/core/intent/{DefaultIntentClassifier,IntentResolver}.java`；`rag/enums/IntentKind.java`；`rag/core/guidance/IntentGuidanceService.java`
- 分块：`core/chunk/ChunkingMode.java`、`core/chunk/strategy/*`、`core/chunk/AbstractEmbeddingChunker.java`
- 入库富集：`ingestion/node/EnricherNode.java`、`ingestion/domain/enums/ChunkEnrichType.java`
- 关键 grep（业务代码）：`GraphRAG|Self-RAG|CRAG|reflect|反思|纠错|多模态|HyDE|step-back|parent-child|small-to-big` → 无业务命中（仅 `java.lang.reflect` 反射与接口注释里的"多模态/混合检索"文字描述）。
- Spring AI grep：`org.springframework.ai|spring-ai|QuestionAnswerAdvisor|Advisor` → 全仓 0 命中。
- 依赖版本：`pom.xml`（Spring Boot 3.5.7、milvus-sdk 2.6.6、tika 3.2.3；无 Spring AI/图数据库依赖）。
- 规划佐证：`docs/multi-channel-retrieval.md`（ES 关键词通道、混合检索列为"未来扩展"）。

> 未验证项：是否存在其他路径消费 `metadata.summary/keywords` 用于检索（当前检索侧未见引用）；本地 reranker/embedding 的可替换性仅从接口层推断，未实测。
