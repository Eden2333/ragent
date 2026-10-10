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

package com.nageoffer.ai.ragent.mcp.mysql.datasource;

import com.nageoffer.ai.ragent.mcp.mysql.config.MySQLMcpProperties;
import com.nageoffer.ai.ragent.mcp.mysql.tool.MySQLMcpToolException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL MCP 数据源管理：配置校验、Hikari 连接池懒加载缓存、只读执行模板
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLDataSourceManager implements DisposableBean {

    private static final String MYSQL_URL_PREFIX = "jdbc:mysql://";

    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_$\\-]{1,64}$");

    private static final int MAX_MESSAGE_LENGTH = 500;

    private static final String[][] UNSAFE_FLAGS = {
            {"allowmultiqueries", "禁止开启 allowMultiQueries"},
            {"allowloadlocalinfile", "禁止开启 allowLoadLocalInfile"},
            {"allowurlinlocalinfile", "禁止开启 allowUrlInLocalInfile"}
    };

    private final MySQLMcpProperties properties;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    private Set<String> validNames = Set.of();

    private volatile boolean closed;

    @FunctionalInterface
    public interface ConnectionCallback<T> {

        T doInConnection(Connection conn) throws SQLException;
    }

    @PostConstruct
    public void init() {
        Set<String> valid = new LinkedHashSet<>();
        properties.getDatasources().forEach((name, ds) -> {
            String reason = validate(ds);
            if (reason != null) {
                log.error("MySQL MCP 数据源[{}]配置非法，已跳过: {}", name, reason);
            } else {
                valid.add(name);
            }
        });
        this.validNames = Set.copyOf(valid);
        log.info("MySQL MCP 可用数据源: {}", validNames);
    }

    private String validate(MySQLMcpProperties.DatasourceProperties ds) {
        if (ds == null || ds.getUrl() == null || ds.getUrl().isBlank()) {
            return "url 不能为空";
        }
        String url = ds.getUrl().trim().toLowerCase(Locale.ROOT);
        if (!url.startsWith(MYSQL_URL_PREFIX)) {
            return "url 必须以 jdbc:mysql:// 开头";
        }
        if (ds.getUsername() == null || ds.getUsername().isBlank()) {
            return "username 不能为空";
        }
        for (String[] flag : UNSAFE_FLAGS) {
            if (isFlagEnabled(url, flag[0])) {
                return flag[1];
            }
        }
        return null;
    }

    /**
     * URL 中出现该参数且值不是 false/no 时视为开启
     */
    private boolean isFlagEnabled(String lowerUrl, String flag) {
        Matcher matcher = Pattern.compile("[?&]\\s*" + flag + "\\s*=\\s*([^&]*)").matcher(lowerUrl);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!"false".equals(value) && !"no".equals(value)) {
                return true;
            }
        }
        // 未带值的写法（如 ?allowMultiQueries）同样视为开启
        return Pattern.compile("[?&]\\s*" + flag + "\\s*(&|$)").matcher(lowerUrl).find();
    }

    public boolean isAvailable(String name) {
        return name != null && validNames.contains(name);
    }

    public Set<String> availableNames() {
        return validNames;
    }

    /**
     * 从 URL 解析默认库名，仅用于展示
     */
    public String defaultDatabase(String name) {
        MySQLMcpProperties.DatasourceProperties ds = properties.getDatasources().get(name);
        if (ds == null || ds.getUrl() == null) {
            return null;
        }
        String url = ds.getUrl().trim();
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        String rest = path.substring(Math.min(MYSQL_URL_PREFIX.length(), path.length()));
        int slash = rest.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String db = rest.substring(slash + 1);
        return db.isEmpty() ? null : db;
    }

    /**
     * 在只读连接 + 只读事务中执行回调，结束后回滚
     *
     * @param name           数据源名称
     * @param database       可选，切换到的库
     * @param callback       回调
     * @param timeoutSeconds 查询超时
     */
    public <T> T executeReadOnly(String name, String database, ConnectionCallback<T> callback, int timeoutSeconds) throws SQLException {
        if (!isAvailable(name)) {
            throw new MySQLMcpToolException("数据源不存在或无访问权限: " + name);
        }
        if (database != null && !DATABASE_NAME_PATTERN.matcher(database).matches()) {
            throw new MySQLMcpToolException("非法的 database 名称");
        }
        HikariDataSource ds;
        try {
            ds = getOrCreate(name);
        } catch (MySQLMcpToolException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MySQL MCP 数据源[{}]连接池初始化失败: {}", name, sanitize(e.getMessage()));
            throw new MySQLMcpToolException("数据源[" + name + "]连接失败，请联系服务端检查配置");
        }
        Connection conn;
        try {
            conn = ds.getConnection();
        } catch (SQLException e) {
            log.warn("MySQL MCP 数据源[{}]获取连接失败: {}", name, sanitize(e.getMessage()));
            throw new MySQLMcpToolException("数据源[" + name + "]连接失败，请联系服务端检查配置");
        }
        try (conn) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            if (database != null) {
                conn.setCatalog(database);
            }
            try (Statement s = conn.createStatement()) {
                s.setQueryTimeout(timeoutSeconds);
                s.execute("START TRANSACTION READ ONLY");
            }
            try {
                return callback.doInConnection(conn);
            } finally {
                safeRollback(conn);
            }
        }
    }

    private HikariDataSource getOrCreate(String name) {
        if (closed) {
            throw new MySQLMcpToolException("服务正在关闭");
        }
        return pools.computeIfAbsent(name, this::createPool);
    }

    private HikariDataSource createPool(String name) {
        MySQLMcpProperties.DatasourceProperties cfg = properties.getDatasources().get(name);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(cfg.getUrl().trim());
        config.setUsername(cfg.getUsername());
        config.setPassword(cfg.getPassword());
        config.setPoolName("mcp-mysql-" + name);
        config.setMaximumPoolSize(Math.max(1, cfg.getMaximumPoolSize()));
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("allowMultiQueries", "false");
        config.addDataSourceProperty("allowLoadLocalInfile", "false");
        config.addDataSourceProperty("allowUrlInLocalInfile", "false");
        return new HikariDataSource(config);
    }

    private void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("MySQL MCP 回滚只读事务失败: {}", sanitize(e.getMessage()));
        }
    }

    /**
     * 脱敏异常消息：替换已配置的密码并截断
     */
    public String sanitize(String msg) {
        if (msg == null) {
            return "";
        }
        String result = msg;
        for (MySQLMcpProperties.DatasourceProperties ds : properties.getDatasources().values()) {
            if (ds != null && ds.getPassword() != null && !ds.getPassword().isEmpty()) {
                result = result.replace(ds.getPassword(), "******");
            }
        }
        return result.length() > MAX_MESSAGE_LENGTH ? result.substring(0, MAX_MESSAGE_LENGTH) + "..." : result;
    }

    @Override
    public void destroy() {
        closed = true;
        int count = 0;
        for (HikariDataSource ds : pools.values()) {
            try {
                ds.close();
                count++;
            } catch (Exception e) {
                log.warn("MySQL MCP 关闭连接池[{}]失败: {}", ds.getPoolName(), sanitize(e.getMessage()));
            }
        }
        pools.clear();
        log.info("MySQL MCP 已关闭 {} 个连接池", count);
    }
}
