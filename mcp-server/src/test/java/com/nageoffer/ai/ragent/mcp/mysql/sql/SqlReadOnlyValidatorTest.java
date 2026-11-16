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

package com.nageoffer.ai.ragent.mcp.mysql.sql;

import com.nageoffer.ai.ragent.mcp.mysql.tool.MySQLMcpToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 只读 SQL 校验用例：合法查询必须放行（避免误拒），变更/绕过类语句必须拒绝
 */
class SqlReadOnlyValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from t_user where id = 1",
            "SELECT id, name FROM t_knowledge_base ORDER BY id DESC LIMIT 10;",
            "  select count(*) from t_user  ",
            "WITH t AS (SELECT 1 AS x) SELECT x FROM t",
            "SHOW TABLES",
            "SHOW CREATE TABLE t_user",
            "SHOW CHARACTER SET",
            "DESC t_user",
            "DESCRIBE t_user",
            "EXPLAIN SELECT * FROM t_user",
            "SELECT REPLACE(name, 'a', 'b') FROM t_user",
            "SELECT INSERT('abc', 1, 1, 'x')",
            "SELECT CAST(name AS CHAR CHARACTER SET utf8mb4) FROM t_user",
            "SELECT 'delete from t; drop table x' AS s",
            "SELECT `update`, `delete` FROM t_user",
            "SELECT 'it''s' AS s",
            "SELECT 1 -- trailing comment",
            "SELECT 1 # hash comment",
            "SELECT /* plain comment */ 1"
    })
    void allowsReadOnlyQueries(String sql) {
        assertDoesNotThrow(() -> SqlReadOnlyValidator.validate(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 变更与 DDL
            "DELETE FROM t_user",
            "UPDATE t_user SET name = 'x'",
            "INSERT INTO t_user VALUES (1)",
            "REPLACE INTO t_user VALUES (1)",
            "DROP TABLE t_user",
            "TRUNCATE TABLE t_user",
            "ALTER TABLE t_user ADD c INT",
            "CREATE TABLE x (id INT)",
            "RENAME TABLE a TO b",
            "GRANT ALL ON *.* TO 'u'",
            // 会话、事务、过程与管理语句
            "SET @a = 1",
            "CALL some_proc()",
            "DO SLEEP(1)",
            "HANDLER t_user OPEN",
            "LOCK TABLES t_user READ",
            "LOAD DATA INFILE 'x' INTO TABLE t_user",
            "START TRANSACTION",
            "PREPARE s FROM 'DELETE FROM t'",
            "KILL 123",
            "SHUTDOWN",
            // 多语句（含注释换行、大小写变体）
            "SELECT 1; DROP TABLE t_user",
            "SELECT 1; SELECT 2",
            "sElEcT 1; dElEtE from t_user",
            "SELECT 1 -- x\n; DROP TABLE t_user",
            "SELECT 1 #x\n; DELETE FROM t_user",
            // 只读外壳里的写操作
            "WITH t AS (SELECT 1) DELETE FROM t_user",
            "WITH t AS (SELECT 1) UPDATE t_user SET name = 'x'",
            "EXPLAIN DELETE FROM t_user",
            // 导出文件、变量与锁
            "SELECT * FROM t_user INTO OUTFILE '/tmp/x'",
            "SELECT * FROM t_user INTO DUMPFILE '/tmp/x'",
            "SELECT 1 INTO @v",
            "SELECT @v := 1",
            "SELECT * FROM t_user FOR UPDATE",
            "SELECT * FROM t_user FOR SHARE",
            "SELECT * FROM t_user LOCK IN SHARE MODE",
            // 拖慢或探测数据库的函数
            "SELECT SLEEP(5)",
            "SELECT SLEEP (5)",
            "SELECT BENCHMARK(1000000, MD5('a'))",
            "SELECT GET_LOCK('x', 10)",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT name FROM t_user UNION SELECT LOAD_FILE('/etc/passwd')",
            // 可执行注释与优化器提示
            "SELECT 1 /*!50000 , SLEEP(5) */",
            "SELECT /*+ MAX_EXECUTION_TIME(1) */ 1",
            "SELECT /*M! 1 */ 1",
            // 解析边界
            "SELECT 1 /* unclosed",
            "SELECT 'unclosed",
            "SELECT `unclosed",
            "SELECT 'it\\'s'",
            "SELECT {fn NOW()}"
    })
    void rejectsMutatingOrBypassingStatements(String sql) {
        assertThrows(MySQLMcpToolException.class, () -> SqlReadOnlyValidator.validate(sql));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlank(String sql) {
        assertThrows(MySQLMcpToolException.class, () -> SqlReadOnlyValidator.validate(sql));
    }

    @Test
    void rejectsOversizedSql() {
        assertThrows(MySQLMcpToolException.class, () -> SqlReadOnlyValidator.validate("SELECT " + "1".repeat(70000)));
    }
}
