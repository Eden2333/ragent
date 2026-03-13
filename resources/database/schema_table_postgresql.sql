-- PostgreSQL 数据库建表脚本
-- 用于 Ragent 项目

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 会话列表表
CREATE TABLE IF NOT EXISTS t_conversation (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    last_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_user_time ON t_conversation(user_id, last_time);

-- 会话摘要表
CREATE TABLE IF NOT EXISTS t_conversation_summary (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    last_message_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_conv_user ON t_conversation_summary(conversation_id, user_id);

-- 摄取流水线定义表
CREATE TABLE IF NOT EXISTS t_ingestion_pipeline (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_by VARCHAR(64) DEFAULT '',
    updated_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted)
);

-- 摄取流水线节点配置表
CREATE TABLE IF NOT EXISTS t_ingestion_pipeline_node (
    id BIGINT PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    next_node_id VARCHAR(64),
    settings_json JSONB,
    condition_json JSONB,
    created_by VARCHAR(64) DEFAULT '',
    updated_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted)
);
CREATE INDEX IF NOT EXISTS idx_ingestion_pipeline_node_pipeline ON t_ingestion_pipeline_node(pipeline_id);

-- 摄取任务记录表
CREATE TABLE IF NOT EXISTS t_ingestion_task (
    id BIGINT PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_location TEXT,
    source_file_name VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    chunk_count INT DEFAULT 0,
    error_message TEXT,
    logs_json JSONB,
    metadata_json JSONB,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_by VARCHAR(64) DEFAULT '',
    updated_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_pipeline ON t_ingestion_task(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_status ON t_ingestion_task(status);

-- 摄取任务节点执行记录表
CREATE TABLE IF NOT EXISTS t_ingestion_task_node (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    pipeline_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    node_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    message TEXT,
    error_message TEXT,
    output_json TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_node_task ON t_ingestion_task_node(task_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_node_pipeline ON t_ingestion_task_node(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_task_node_status ON t_ingestion_task_node(status);

-- RAG意图树节点配置表
CREATE TABLE IF NOT EXISTS t_intent_node (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT,
    intent_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    level SMALLINT NOT NULL,
    parent_code VARCHAR(64),
    description VARCHAR(512),
    examples TEXT,
    collection_name VARCHAR(128),
    top_k INT,
    mcp_tool_id VARCHAR(128),
    kind SMALLINT NOT NULL DEFAULT 0,
    prompt_snippet TEXT,
    prompt_template TEXT,
    param_prompt_template TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    enabled SMALLINT NOT NULL DEFAULT 1,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- RAG知识库表
CREATE TABLE IF NOT EXISTS t_knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    collection_name VARCHAR(128) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);
CREATE INDEX IF NOT EXISTS idx_kb_name ON t_knowledge_base(name);

-- RAG知识库文档分块表
CREATE TABLE IF NOT EXISTS t_knowledge_chunk (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    doc_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64),
    char_count INT,
    token_count INT,
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_doc_id ON t_knowledge_chunk(doc_id);

-- RAG知识库文档表
CREATE TABLE IF NOT EXISTS t_knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    doc_name VARCHAR(256) NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    chunk_count INT DEFAULT 0,
    file_url VARCHAR(1024) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    file_size BIGINT,
    process_mode VARCHAR(32) DEFAULT 'chunk',
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    source_type VARCHAR(32),
    source_location VARCHAR(1024),
    schedule_enabled SMALLINT,
    schedule_cron VARCHAR(128),
    chunk_strategy VARCHAR(32),
    chunk_config JSONB,
    pipeline_id BIGINT,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_id ON t_knowledge_document(kb_id);

-- 知识库文档分块日志表
CREATE TABLE IF NOT EXISTS t_knowledge_document_chunk_log (
    id BIGINT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    process_mode VARCHAR(20),
    chunk_strategy VARCHAR(50),
    pipeline_id BIGINT,
    extract_duration BIGINT,
    chunk_duration BIGINT,
    embedding_duration BIGINT,
    total_duration BIGINT,
    chunk_count INT,
    error_message TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_doc_id_log ON t_knowledge_document_chunk_log(doc_id);

-- 知识库文档定时刷新任务表
CREATE TABLE IF NOT EXISTS t_knowledge_document_schedule (
    id BIGINT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    cron_expr VARCHAR(128),
    enabled SMALLINT DEFAULT 0,
    next_run_time TIMESTAMP,
    last_run_time TIMESTAMP,
    last_success_time TIMESTAMP,
    last_status VARCHAR(32),
    last_error VARCHAR(512),
    last_etag VARCHAR(256),
    last_modified VARCHAR(256),
    last_content_hash VARCHAR(128),
    lock_owner VARCHAR(128),
    lock_until TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX IF NOT EXISTS idx_next_run ON t_knowledge_document_schedule(next_run_time);
CREATE INDEX IF NOT EXISTS idx_lock_until ON t_knowledge_document_schedule(lock_until);

-- 知识库文档定时刷新执行记录表
CREATE TABLE IF NOT EXISTS t_knowledge_document_schedule_exec (
    id BIGINT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    doc_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(512),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    file_name VARCHAR(512),
    file_size BIGINT,
    content_hash VARCHAR(128),
    etag VARCHAR(256),
    last_modified VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_schedule_time ON t_knowledge_document_schedule_exec(schedule_id, start_time);
CREATE INDEX IF NOT EXISTS idx_doc_id_exec ON t_knowledge_document_schedule_exec(doc_id);

-- 会话消息记录表
CREATE TABLE IF NOT EXISTS t_message (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_conversation_user_time ON t_message(conversation_id, user_id, create_time);

-- 会话消息反馈表
CREATE TABLE IF NOT EXISTS t_message_feedback (
    id BIGINT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    vote SMALLINT NOT NULL,
    reason VARCHAR(255),
    comment VARCHAR(1024),
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_msg_user UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_conversation_id ON t_message_feedback(conversation_id);
CREATE INDEX IF NOT EXISTS idx_user_id ON t_message_feedback(user_id);

-- RAG关键词归一化映射表
CREATE TABLE IF NOT EXISTS t_query_term_mapping (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(64),
    source_term VARCHAR(128) NOT NULL,
    target_term VARCHAR(128) NOT NULL,
    match_type SMALLINT NOT NULL DEFAULT 1,
    priority INT NOT NULL DEFAULT 100,
    enabled SMALLINT NOT NULL DEFAULT 1,
    remark VARCHAR(255),
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_domain ON t_query_term_mapping(domain);
CREATE INDEX IF NOT EXISTS idx_source ON t_query_term_mapping(source_term);

-- RAG Trace 节点记录表
CREATE TABLE IF NOT EXISTS t_rag_trace_node (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    parent_node_id VARCHAR(64),
    depth INT DEFAULT 0,
    node_type VARCHAR(64),
    node_name VARCHAR(128),
    class_name VARCHAR(256),
    method_name VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    error_message VARCHAR(1000),
    start_time TIMESTAMP(3),
    end_time TIMESTAMP(3),
    duration_ms BIGINT,
    extra_data TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_run_node UNIQUE (trace_id, node_id)
);

-- RAG Trace 运行记录表
CREATE TABLE IF NOT EXISTS t_rag_trace_run (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    trace_name VARCHAR(128),
    entry_method VARCHAR(256),
    conversation_id VARCHAR(64),
    task_id VARCHAR(64),
    user_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    error_message VARCHAR(1000),
    start_time TIMESTAMP(3),
    end_time TIMESTAMP(3),
    duration_ms BIGINT,
    extra_data TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_run_id UNIQUE (trace_id)
);
CREATE INDEX IF NOT EXISTS idx_task_id ON t_rag_trace_run(task_id);
CREATE INDEX IF NOT EXISTS idx_user_id_trace ON t_rag_trace_run(user_id);

-- 示例问题表
CREATE TABLE IF NOT EXISTS t_sample_question (
    id BIGINT PRIMARY KEY,
    title VARCHAR(64),
    description VARCHAR(255),
    question VARCHAR(1024) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sample_question_deleted ON t_sample_question(deleted);

-- 系统用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    avatar VARCHAR(128),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
