# Ragent 优缺点与主流 RAG Agent 差距分析

> 分析日期：2026-11-11，基于提交 `a8e0a5e`。
> 方法：阅读源码、grep 交叉核对、核对表结构与 `application.yaml`。未启动服务实测，第二节第 1～3 条建议本地复现确认。
> 本文替换旧版成熟度报告。旧版建议的"意图向量粗筛 + LLM 精筛"已由 `VectorRecallIntentClassifier` 实现，灰区检索也已改为"先定向、按需补全量"。

---

## 结论

Ragent 的工程能力（模型容错、限流、可编排入库、意图体系）在开源 RAG 项目里属于中上水平。但它的检索内核和编排方式仍是"固定管道 + 阈值分支"，算不上 Agentic RAG。代码里还有几处会直接影响结果正确性的问题，应该先修。

主流程见 `RAGChatServiceImpl#streamChat`：记忆加载 → 改写拆分 → 意图解析 → 歧义引导 → 检索（MCP + KB）→ Prompt 组装 → 流式输出。每一步按固定顺序执行，检索结果差时不会改写重试，也不会由模型决定下一步。

---

## 一、优点

| 能力 | 代码位置 | 评价 |
|---|---|---|
| 模型路由与容错 | `RoutingLLMService` 中的 `ProbeBufferingCallback`、`ModelHealthStore` | 先缓冲首包，确认成功后再回放，切换模型时用户收不到半截内容；配合三态熔断。多数开源 RAG 项目没有做到这一层 |
| 分布式排队限流 | `ChatQueueLimiter`、`bootstrap/src/main/resources/lua/queue_claim_atomic.lua` | 用 ZSET 排队、Lua 原子出队、可过期信号量、Pub/Sub 唤醒，设计完整 |
| 意图体系 | `VectorRecallIntentClassifier`、`IntentGuidanceService`、`MultiChannelRetrievalEngine` | 树形意图 + 向量粗筛 + LLM 精筛 + 同名意图扩展 + 歧义时反问用户；灰区先定向检索、按需补全量；SYSTEM 意图直接跳过检索 |
| 可插拔扩展点 | `SearchChannel`、`SearchResultPostProcessor`、`IngestionNode`、`ChatClient` | 新增一个实现类即可生效 |
| 入库流水线 | `IngestionEngine`，以及 `ingestion/strategy/fetcher/*` 下的本地、HTTP、S3、飞书拉取 | 节点可配置，支持条件执行、节点日志和定时拉取 |
| 会话记忆 | `MySQLConversationMemorySummaryService` | 异步滚动摘要，Redisson 锁防止并发重复摘要 |
| Rerank | `RerankPostProcessor`、`RoutingRerankService`、`BaiLianRerankClient`、`NoopRerankClient` | 有真实的精排模型，带路由降级和空实现兜底 |
| 分层与可读性 | framework / infra-ai / bootstrap 三个模块 | 没有绑定 Spring AI 或 LangChain4j，注释充分，适合学习 |

---

## 二、代码级问题（建议优先修）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | Pipeline 模式写入的向量缺少 `kb_id`/`doc_id` | `IndexerNode#buildRows` 只写了 `chunk_index`、`task_id`、`pipeline_id`、`source_*`；`KnowledgeDocumentServiceImpl#runPipelineProcess` 构建 `IngestionContext` 时也没有传入这两个字段。而 `MilvusVectorStoreService#deleteDocumentVectors` 按 `metadata["kb_id"]` 和 `metadata["doc_id"]` 过滤删除 | ⚠️避坑：Pipeline 模式下删除、禁用或重新分块文档时，旧向量删不掉。已删除的内容仍会被召回，重建时向量会重复 |
| 2 | 查询向量和库内向量可能来自不同模型 | 写入侧按知识库的 `embedding_model` 生成向量（`KnowledgeChunkServiceImpl`、`AbstractEmbeddingChunker`）；查询侧 `MilvusRetrieverService#retrieve` 调用 `embeddingService.embed(query)`，走的是默认模型加跨模型降级（`RoutingEmbeddingService#embed`）。`application.yaml` 里两个 embedding 候选维度分别是 1024 和 4096 | ⚠️避坑：一旦降级，维度不一致，检索报错后静默返回空。如果两个模型维度恰好相同，结果会是无声的错误召回。Embedding 只能降级到同一模型的副本，不能换模型 |
| 3 | Tika 默认截断长文档 | `TikaDocumentParser` 用 `new Tika().parseToString(...)`，没有设置 `maxStringLength`，Tika 默认上限是 10 万字符 | ⚠️避坑：长 PDF 或 Word 超出部分被静默丢弃，日志里没有任何提示 |
| 4 | Rerank 之后没有相关性阈值 | `RerankPostProcessor` 固定返回 topK（`DEFAULT_TOP_K=10`）；常量 `RAGConstant.SCORE_MARGIN_RATIO` 定义了但没被使用；ANN 检索几乎总有结果 | `RAGChatServiceImpl` 中 `ctx.isEmpty()` 判断后的"未检索到"回复基本走不到。低相关的分块总会被塞进上下文，拒答只能靠 prompt 约束 |
| 5 | MCP 工具结果以 system 角色注入 | `RAGPromptService#buildStructuredMessages` 中的 `ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, …))` | 外部工具返回的内容拿到了 system 级权限，扩大了 prompt 注入的攻击面。知识库内容用的是 user 角色，处理是对的 |
| 6 | 全局检索时同一个问题被重复向量化 | `CollectionParallelRetriever#createRetrievalTask` 对每个 collection 都调用一次 `retrieverService.retrieve`，每次都重新计算问题向量 | 有 N 个知识库就多出 N 次相同的 embedding 调用 |
| 7 | Trace 同步写库，记录的信息也少 | `RagTraceRecordServiceImpl` 每个节点在请求线程里做一次 insert 和一次 update；`RagTraceAspect` 只记录耗时、状态和异常。全仓搜索 `usage` 和 `prompt_tokens` 均无结果 | 拖慢主链路；README 说会记录"输入输出"，实际没有，token 成本也无法统计 |
| 8 | 首个 token 前要串行调用 3 次以上 LLM | 改写（`MultiQuestionRewriteService`）→ 意图打分（`IntentResolver`）→ MCP 参数抽取（`LLMMCPParameterExtractor`）→ 生成；这期间 `StreamChatEventHandler` 只在开始时发送了 META 事件 | 首字延迟高，等待期间用户看不到任何进度 |

---

## 三、与主流 RAG Agent 的差距

2026 年主流企业 RAG 已经从线性管道转向"由模型决定查哪个源、查几轮"的编排方式，并且要求权限过滤在查询时、在索引层完成。忠实度等指标通过 RAGAS、TruLens 这类评测作为上线门槛（[Atolio 2026 企业 RAG 指南](https://www.atolio.com/blog/enterprise-rag-guide)）。RAGFlow 0.27 引入了"知识编译"：把文档整理成 Wiki、Graph、Tree、Page Index 等结构化知识资产，并升级了 Agentic Retrieval（[RAGFlow 0.27](https://ragflow.io/blog/ragflow-0.27-knowledge-compilation-and-agentic-retrieval)）。以上内容已转述。

| 维度 | Ragent 现状 | 主流做法（代表项目） | 差距 |
|---|---|---|---|
| 编排方式 | 固定管道，靠阈值分支；检索结果差时不会重试 | 检索本身作为一个工具，模型决定检索几轮、何时停止，并对检索质量自评（CRAG 思路）。代表：LangGraph、RAGFlow Agentic 模式、Spring AI Alibaba Graph | 大 |
| 工具调用 | 意图节点静态绑定 `mcpToolId`，参数由 LLM 单独抽取，只执行一次；`ChatRequest.enableTools` 字段预留但没有实现；`HttpMCPClient` 不处理 `Mcp-Session-Id`、SSE 响应和鉴权 | 原生 function calling，MCP 工具动态发现，工具结果多轮反馈给模型 | 大 |
| 召回 | 纯稠密向量（`MilvusVectorStoreAdmin` 只有一个 `FloatVector` 字段），两个"通道"区别只是查哪些 collection；`SearchChannelType.KEYWORD_ES` 枚举没有实现类 | BM25/稀疏向量 + 稠密向量，RRF 融合后再 rerank。Milvus 2.5 起原生支持 BM25，项目已在用 2.6 | 大，但改动成本低 |
| 文档理解 | Tika 纯文本抽取，表格、版面、扫描件都会丢信息 | 版面分析、表格识别、OCR（RAGFlow DeepDoc、MinerU、Docling），页面图像检索 | 大 |
| 分块与索引 | 4 种单层平铺分块（固定、结构感知、句子、段落） | 父子块（检索小块、送大块）、Contextual Retrieval（给分块补上文档上下文）、知识编译 | 中到大 |
| 权限 | `t_knowledge_base` 没有归属或可见范围字段，`t_user` 只有 admin/user 两种角色；`VectorGlobalSearchChannel#getAllKBCollections` 会查所有知识库 | 文档级 ACL，在查询时于索引层过滤（Onyx、Glean） | 大，这是企业落地的硬门槛 |
| 引用溯源 | `RetrievedChunk` 只有 id、text、score；Milvus 元数据里没有文档名或标题路径；`DefaultContextFormatter` 用换行直接拼接分块；`answer-chat-kb.st` 要求"默认不提来源"；SSE 没有发送引用事件 | 正文内标注引用，并附可点击的来源卡片 | 大 |
| 效果评测 | 没有离线评测集；点赞/点踩数据（`t_message_feedback`）没有关联到 trace 和召回的分块 | 维护回归测试集，忠实度、上下文精度等指标作为发布门禁 | 大 |
| 可观测性 | 自研 AOP，只记录耗时和状态 | OpenTelemetry GenAI 规范，接 Langfuse 或 Phoenix，记录 prompt、输出、token 和成本 | 中 |
| 长期记忆 | 滑动窗口 + 滚动摘要；滑出窗口的原文无法再召回 | 历史对话向量化召回、用户画像（mem0 类） | 中 |
| 多跳推理 | 无 | GraphRAG、LightRAG | 视场景而定 |
| 测试 | 11 个测试类全部是 `@SpringBootTest`，依赖真实的 MySQL、Redis、Milvus 和 LLM；核心逻辑没有单元测试 | 核心逻辑写单元测试，评测集跑回归 | 中 |
| 模型工程 | 路由 + 首包探测 + 熔断 | 同类网关（LiteLLM、Higress） | Ragent 领先 |

⚠️避坑：README 写了"Agentic RAG"和"多租户隔离与权限控制"，代码里这两项都没有落地。拿去面试或给企业演示时容易被追问。

---

## 四、改进优先级

| 优先级 | 内容 | 投入 |
|---|---|---|
| P0 修正确性 | 第二节第 1～5 条：Pipeline 写入补上 `kb_id`/`doc_id`；查询按知识库的 `embedding_model` 生成向量，并禁止跨模型降级；Tika 设置 `setMaxStringLength(-1)` 或改用流式处理；rerank 之后加绝对分和相对分阈值；MCP 结果改用 user 角色并加边界标记 | 小 |
| P1 补企业门槛 | 知识库可见范围 + Milvus filter 或 partition key 做查询期过滤；分块元数据补充文档名和标题路径，上下文加编号，SSE 发送 references 事件；用 Milvus BM25 + `hybridSearch` + RRF 做混合检索；建立离线评测集，把用户反馈关联到 trace | 中 |
| P2 向 Agent 演进 | 在 `ChatClient` 里实现原生 tool calling；把检索做成有轮数上限的工具循环（最多 2～3 轮，带检索质量自评）；MCP 动态发现工具；trace 改为异步的 OTel 导出 | 大 |
| P3 按场景选做 | 版面解析作为 Parser 节点接入（MinerU/Docling）、父子块与 Contextual Retrieval、GraphRAG、长期记忆 | 大 |

---

## 五、证据索引（可复核）

路径均相对 `bootstrap/src/main/java/com/nageoffer/ai/ragent/`，另有说明的除外。

- 主流程：`rag/service/impl/RAGChatServiceImpl.java#streamChat`
- 检索编排：`rag/core/retrieve/RetrievalEngine.java`、`rag/core/retrieve/MultiChannelRetrievalEngine.java`
- 向量检索：`rag/core/retrieve/MilvusRetrieverService.java#retrieve`、`#retrieveByVector`
- 向量 schema：`rag/core/vector/MilvusVectorStoreAdmin.java#ensureVectorSpace`
- 向量写入与删除：`rag/core/vector/MilvusVectorStoreService.java#indexDocumentChunks`、`#deleteDocumentVectors`；`ingestion/node/IndexerNode.java#buildRows`
- Pipeline 入库：`knowledge/service/impl/KnowledgeDocumentServiceImpl.java#runPipelineProcess`
- 检索通道：`rag/core/retrieve/channel/IntentDirectedSearchChannel.java`、`VectorGlobalSearchChannel.java`、`strategy/CollectionParallelRetriever.java`
- 后处理：`rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java`、`RerankPostProcessor.java`
- Prompt 组装：`rag/core/prompt/RAGPromptService.java#buildStructuredMessages`、`rag/core/prompt/DefaultContextFormatter.java`
- 意图：`rag/core/intent/{DefaultIntentClassifier,VectorRecallIntentClassifier,IntentResolver}.java`
- MCP：`rag/core/mcp/client/HttpMCPClient.java`、`rag/core/mcp/LLMMCPParameterExtractor.java`
- Trace：`rag/aop/RagTraceAspect.java`、`rag/service/impl/RagTraceRecordServiceImpl.java`
- 解析：`core/parser/TikaDocumentParser.java`
- 模型路由（infra-ai 模块）：`infra/chat/RoutingLLMService.java`、`infra/embedding/RoutingEmbeddingService.java`
- 配置与表结构：`bootstrap/src/main/resources/application.yaml`、`resources/database/schema_table.sql`
- 关键 grep：`prompt_tokens|completion_tokens|"usage"` 全仓 0 命中；`SCORE_MARGIN_RATIO` 仅在定义处出现；`Mcp-Session-Id|Authorization|text/event-stream` 在 MCP 相关代码中 0 命中。

> 未验证项：Pipeline 模式下 `EnricherNode` 生成的关键词和摘要是否参与向量化（`IndexerNode` 注释提到存储内容与 embedding 文本不同，未深入核对）。
