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

package com.nageoffer.ai.ragent.mcp.mysql.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.endpoint.MySQLMcpEndpoint;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpAuthFilter;
import com.nageoffer.ai.ragent.mcp.mysql.auth.MySQLMcpTokenAuthenticator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * MySQL MCP 端点配置：绑定配置属性，并仅在 /mcp/mysql-mcp 上注册 Bearer token 鉴权过滤器
 */
@Configuration
@EnableConfigurationProperties(MySQLMcpProperties.class)
public class MySQLMcpConfiguration {

    @Bean
    public FilterRegistrationBean<MySQLMcpAuthFilter> mySQLMcpAuthFilterRegistration(MySQLMcpTokenAuthenticator authenticator,
                                                                                     ObjectMapper objectMapper) {
        FilterRegistrationBean<MySQLMcpAuthFilter> bean = new FilterRegistrationBean<>(new MySQLMcpAuthFilter(authenticator, objectMapper));
        bean.addUrlPatterns(MySQLMcpEndpoint.PATH, MySQLMcpEndpoint.PATH + "/*");
        bean.setName("mySQLMcpAuthFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
