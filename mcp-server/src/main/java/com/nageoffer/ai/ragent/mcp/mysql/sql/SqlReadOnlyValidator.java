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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL（DQL）静态校验器
 * <p>
 * 先剥离注释、字符串字面量和反引号标识符，再检查语句数量与关键字。
 * 校验为 fail-closed：无法确定安全的写法一律拒绝。
 */
public final class SqlReadOnlyValidator {

    private static final int MAX_SQL_LENGTH = 65536;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_$]+");

    private static final Set<String> ALLOWED_FIRST = Set.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");

    private static final Set<String> BLOCKED = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "UPSERT",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
            "GRANT", "REVOKE",
            "SET", "CALL", "DO", "HANDLER", "LOCK", "UNLOCK", "LOAD", "IMPORT",
            "INTO", "OUTFILE", "DUMPFILE", "SHARE",
            "PREPARE", "EXECUTE", "DEALLOCATE",
            "FLUSH", "KILL", "SHUTDOWN", "RESTART", "RESET", "PURGE", "INSTALL", "UNINSTALL", "CLONE",
            "START", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "XA",
            "SLEEP", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK", "RELEASE_ALL_LOCKS", "LOAD_FILE",
            "MASTER_POS_WAIT", "SOURCE_POS_WAIT", "WAIT_FOR_EXECUTED_GTID_SET", "WAIT_UNTIL_SQL_THREAD_AFTER_GTIDS"
    );

    /**
     * 反引号标识符的占位符（非关键字，避免 `update` 这类列名被误判）
     */
    private static final String IDENT_PLACEHOLDER = " _q_ident_ ";

    private static final String LITERAL_PLACEHOLDER = " '' ";

    private SqlReadOnlyValidator() {
    }

    /**
     * 校验 SQL 是否为单条只读查询，不通过时抛出 {@link MySQLMcpToolException}
     *
     * @param sql 待校验 SQL
     */
    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new MySQLMcpToolException("SQL 不能为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new MySQLMcpToolException("SQL 过长");
        }
        checkNormalized(normalize(sql));
    }

    /**
     * 第一遍：剥离注释，将字符串字面量与反引号标识符替换为占位符
     */
    private static String normalize(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            char next = i + 1 < len ? sql.charAt(i + 1) : '\0';
            if (c == '/' && next == '*') {
                char third = i + 2 < len ? sql.charAt(i + 2) : '\0';
                char fourth = i + 3 < len ? sql.charAt(i + 3) : '\0';
                if (third == '!' || third == '+' || ((third == 'M' || third == 'm') && fourth == '!')) {
                    throw new MySQLMcpToolException("禁止使用 MySQL 可执行注释或优化器提示");
                }
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new MySQLMcpToolException("注释未闭合");
                }
                out.append(' ');
                i = end + 2;
            } else if (c == '#') {
                i = skipLine(sql, i);
                out.append(' ');
            } else if (c == '-' && next == '-' && (i + 2 >= len || sql.charAt(i + 2) <= ' ')) {
                i = skipLine(sql, i);
                out.append(' ');
            } else if (c == '\'' || c == '"') {
                i = skipLiteral(sql, i, c);
                out.append(LITERAL_PLACEHOLDER);
            } else if (c == '`') {
                i = skipBacktick(sql, i);
                out.append(IDENT_PLACEHOLDER);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipLine(String sql, int start) {
        int end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    /**
     * 跳过字符串字面量，返回结束引号之后的位置
     */
    private static int skipLiteral(String sql, int start, char quote) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\\') {
                // 是否把反斜杠当转义符取决于服务端 sql_mode（NO_BACKSLASH_ESCAPES），两种模式下 \' 的边界不同，直接拒绝
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    throw new MySQLMcpToolException("字符串中请使用两个引号转义，不支持反斜杠转义引号");
                }
                i += 2;
            } else if (c == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new MySQLMcpToolException("字符串未闭合");
    }

    private static int skipBacktick(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            if (sql.charAt(i) == '`') {
                if (i + 1 < len && sql.charAt(i + 1) == '`') {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        throw new MySQLMcpToolException("标识符未闭合");
    }

    /**
     * 第二遍：在剥离后的文本上检查语句数量与关键字
     */
    private static void checkNormalized(String normalized) {
        String s = normalized.trim();
        while (!s.isEmpty() && (s.charAt(s.length() - 1) == ';' || Character.isWhitespace(s.charAt(s.length() - 1)))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.indexOf(';') >= 0) {
            throw new MySQLMcpToolException("仅允许执行单条 SQL 语句");
        }
        if (s.contains(":=")) {
            throw new MySQLMcpToolException("禁止变量赋值");
        }
        if (s.indexOf('{') >= 0) {
            throw new MySQLMcpToolException("不支持 JDBC 转义语法");
        }

        List<String> tokens = new ArrayList<>();
        List<Character> nextChars = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(s);
        while (matcher.find()) {
            tokens.add(matcher.group().toUpperCase(Locale.ROOT));
            nextChars.add(nextNonWhitespace(s, matcher.end()));
        }
        if (tokens.isEmpty() || !ALLOWED_FIRST.contains(tokens.get(0))) {
            throw new MySQLMcpToolException("仅允许执行查询类语句（SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN）");
        }
        for (int idx = 0; idx < tokens.size(); idx++) {
            String token = tokens.get(idx);
            if (!BLOCKED.contains(token)) {
                continue;
            }
            String prev = idx > 0 ? tokens.get(idx - 1) : "";
            boolean allowed = switch (token) {
                // 字符串函数 REPLACE() / INSERT()
                case "REPLACE", "INSERT" -> nextChars.get(idx) == '(';
                // SHOW CHARACTER SET / CAST(x AS CHAR CHARACTER SET utf8mb4)
                case "SET" -> "CHARACTER".equals(prev) || "CHARSET".equals(prev);
                // SHOW CREATE TABLE t
                case "CREATE" -> "SHOW".equals(prev);
                default -> false;
            };
            if (!allowed) {
                throw new MySQLMcpToolException("禁止的关键字: " + token);
            }
        }
    }

    private static char nextNonWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }
}
