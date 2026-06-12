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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseDO> {

    /**
     * 插入知识库记录，若 collection_name 唯一索引冲突则恢复逻辑删除并更新字段
     */
    @Insert("INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, updated_by, deleted) " +
            "VALUES (#{kb.id}, #{kb.name}, #{kb.embeddingModel}, #{kb.collectionName}, #{kb.createdBy}, #{kb.updatedBy}, 0) " +
            "ON DUPLICATE KEY UPDATE name = VALUES(name), embedding_model = VALUES(embedding_model), " +
            "created_by = VALUES(created_by), updated_by = VALUES(updated_by), deleted = 0")
    int insertOrRestore(@Param("kb") KnowledgeBaseDO kb);
}
