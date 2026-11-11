# 2010 年至今：互联网技术演进与 Java 后端

主线只有一条：**业务规模增长 → 旧架构遇到瓶颈 → 新技术栈出现 → 带来新的复杂度 → 再被新技术抹平**。下面按阶段讲，每段都点出它暴露的 Java 特性和瓶颈。

## 一、总览时间线

| 阶段 | 年份 | 架构关键词 | 代表技术 |
|------|------|-----------|----------|
| 1. 单体 + SSH 末期 | ~2010 前 | 单体、前后端一体 | Struts2 / Spring 3 / Hibernate / JSP |
| 2. 分布式萌芽 | 2010–2014 | SOA、垂直拆分、读写分离、缓存 | Dubbo、ZooKeeper、Redis、Nginx、MyBatis、Maven |
| 3. 前后端分离 + 移动互联网 | 2012–2016 | RESTful、MVVM | Spring MVC、AngularJS/Vue/React、Node 中间层 |
| 4. 微服务 + 容器 | 2014–2018 | 微服务、DevOps、容器化 | Spring Boot、Spring Cloud(Netflix)、Docker、K8s |
| 5. 国产微服务生态 | 2018–2021 | 服务治理标准化 | Spring Cloud Alibaba、Nacos、Sentinel、Seata、RocketMQ |
| 6. 云原生 | 2019–至今 | Service Mesh、Serverless、不可变基础设施 | K8s、Istio、GraalVM、Quarkus |
| 7. Java 语言现代化 | 2018–至今 | 轻量并发、低延迟 | Java 11/17/21、虚拟线程、ZGC |

---

## 二、分阶段详解

### 阶段 1：单体时代（~2010 前）
- **架构**：一个 WAR 包，JSP 渲染页面（前后端一体），所有模块共享一个库。
- **技术**：SSH（Struts + Spring + Hibernate），部署在 Tomcat/WebLogic。
- **Spring 的价值**：IoC/AOP 解决了 EJB 过重的问题，让 POJO 开发成为主流。
- **瓶颈**：
  - 代码耦合、发布牵一发动全身
  - 单库成为性能上限
  - XML 配置地狱

> Java 特性体现：**面向对象 + 反射 + 动态代理**是 Spring 的基石。Spring 本质就是"用反射/代理把对象创建和横切逻辑从代码里拿走"。

### 阶段 2：互联网爆发，分布式萌芽（2010–2014）
**驱动力**：电商、社交增长（淘宝双 11 始于 2009），单机/单库扛不住。

**演进路径（经典套路）**：
1. 加缓存（Memcached → Redis）
2. 读写分离 → 分库分表（MyCat、Sharding-JDBC）
3. 垂直拆分：按业务拆成多个应用
4. 应用间需要通信 → **RPC 框架**登场

**关键技术**：

| 技术 | 作用 |
|------|------|
| **Dubbo**（阿里 2011 开源） | RPC + 服务注册发现 + 负载均衡，国内 SOA 事实标准 |
| **ZooKeeper** | 注册中心、分布式锁、协调 |
| **MyBatis** | 取代 Hibernate，SQL 可控，更适合高并发场景 |
| **Maven** | 依赖管理标准化 |
| **Redis / Kafka / RabbitMQ** | 缓存、削峰、异步解耦 |
| **Nginx** | 反向代理、负载均衡 |

**新问题**：分布式带来**数据一致性（CAP/BASE、分布式事务）**、网络不可靠、调用链难追踪。这些问题至今仍是后端面试和实战的核心。

⚠️避坑：Dubbo 解决的是"调用"，不解决"开发效率"——每个服务仍要手动配 Tomcat、XML，这为 Spring Boot 铺了路。

### 阶段 3：前后端分离（2012–2016）
**为什么发生**：
- 移动 App 兴起，后端要同时服务 Web、iOS、Android，**不能再返回 HTML**，只返回 JSON
- 前端工程化（Node、npm、Webpack）成熟，Angular/Vue/React 让前端自成体系

**变化对比**：

| | 前后端一体 | 前后端分离 |
|---|-----------|-----------|
| 渲染 | 服务端（JSP/Thymeleaf） | 浏览器端（SPA） |
| 后端职责 | 业务 + 页面 | 纯 API（RESTful） |
| 团队 | 全栈一把抓 | 前端/后端并行，靠接口文档约定 |
| 副作用 | — | 跨域、鉴权（Session → Token/JWT）、SEO、接口文档（Swagger） |

**对 Java 的影响**：Java 从"生成页面"退化为"数据服务提供者"，Spring MVC 的 `@RestController` 成为主流，**无状态**成为设计要求（为后续水平扩展打基础）。

### 阶段 4：Spring Boot 与微服务（2014–2018）
**关键事件**：
- **2014**：Spring Boot 1.0 发布；Martin Fowler 发表《Microservices》文章；Docker 开源
- **2015**：Kubernetes 1.0；Netflix OSS 被封装为 **Spring Cloud**

**Spring Boot 的真正贡献**：

| 痛点 | Spring Boot 方案 |
|------|-----------------|
| XML 配置繁琐 | 约定优于配置 + Java Config |
| 依赖版本冲突 | Starter + BOM 统一管理 |
| 部署需外置 Tomcat | 内嵌容器，`java -jar` 即运行 |
| 运维监控缺失 | Actuator |

它让"一个服务 = 一个可独立运行的 jar"变得极其廉价，**这是微服务能在 Java 圈落地的前提**。没有 Boot，拆 100 个服务光配置就能把人累死。

**Spring Cloud（Netflix 套件）**：

| 能力 | 组件 |
|------|------|
| 注册发现 | Eureka |
| 客户端负载均衡 | Ribbon |
| 声明式调用 | Feign |
| 熔断降级 | Hystrix |
| 网关 | Zuul |
| 配置中心 | Config |
| 链路追踪 | Sleuth + Zipkin |

**微服务带来的新复杂度**（必须理解）：
- 分布式事务、最终一致性
- 服务雪崩 → 熔断、限流、降级
- 配置、监控、日志散落各处
- 测试和部署成本剧增

⚠️避坑：微服务不是银弹。小团队、低并发业务强拆微服务，往往是"分布式单体"，比单体还糟。

### 阶段 5：Spring Cloud Alibaba 与国产生态（2018–2021）
**背景**：
- Netflix 2018 年宣布 Hystrix 等进入维护模式，Spring Cloud 出现"组件断档"
- Dubbo 曾停更多年（2014–2017），后阿里重启维护
- 阿里在双 11 大规模实践中沉淀了成熟中间件

**Spring Cloud Alibaba 核心组件**：

| 能力 | 组件 | 取代/对应 |
|------|------|-----------|
| 注册 + 配置中心 | **Nacos** | Eureka + Config |
| 限流熔断 | **Sentinel** | Hystrix |
| 分布式事务 | **Seata** | 无对应（AT/TCC/Saga） |
| 消息 | RocketMQ | Kafka/RabbitMQ 的替代选项 |
| RPC | Dubbo 3（支持 Triple 协议、云原生） | — |

本项目（`dem-odcoreserv` 等）使用 Nacos、Sentinel、Kafka、XXL-Job，正是这一代技术栈的典型组合。

### 阶段 6：云原生（2019–至今）
**理念转变**：基础设施能力从"应用代码里（Spring Cloud）"下沉到"平台层（K8s、Service Mesh）"。

| 能力 | 微服务时代（SDK 内） | 云原生时代（平台层） |
|------|-------------------|---------------------|
| 服务发现 | Eureka/Nacos 客户端 | K8s Service / DNS |
| 负载均衡 | Ribbon | kube-proxy / Sidecar |
| 熔断限流 | Hystrix/Sentinel SDK | Istio / Envoy |
| 配置 | Config Server | ConfigMap |
| 部署 | 虚拟机 + 脚本 | 容器 + 声明式 YAML |

**Service Mesh（Istio）**：用 Sidecar 代理接管通信，业务代码不再感知治理逻辑，**实现语言无关**——这对 Java 是一种挑战，因为 Spring Cloud 的"Java 强绑定"优势被削弱。

### 阶段 7：Java 语言自身的演进
这条线直接回应云原生对 Java 的批评。

| 版本 | 年份 | 关键特性 | 解决的问题 |
|------|------|---------|-----------|
| Java 8 | 2014 | Lambda、Stream、Optional | 函数式表达，Spring 5/WebFlux 基础 |
| Java 9 | 2017 | 模块化（JPMS） | 臃肿的 JDK |
| Java 11 | 2018 | LTS，HttpClient | 成为长期主力版本 |
| Java 17 | 2021 | LTS，record、sealed、ZGC 成熟 | Spring Boot 3 最低要求 |
| Java 21 | 2023 | LTS，**虚拟线程**、模式匹配 | 高并发线程模型 |
| GraalVM Native | 2019+ | AOT 编译 | 启动慢、内存高 |

**Spring Boot 3（2022）**：要求 Java 17+，支持 GraalVM 原生镜像，迁移到 Jakarta EE（`javax.*` → `jakarta.*`）。

---

## 三、从历史看 Java 的特性与瓶颈

| 瓶颈 | 根源 | 业界应对 |
|------|------|---------|
| **启动慢、内存占用高** | JVM 需加载类、JIT 预热；Spring 大量反射 + 运行期代理 | GraalVM Native、Quarkus（编译期处理）、CRaC |
| **线程模型重** | 一个 Java 线程 ≈ 一个 OS 线程（约 1MB 栈），高并发 I/O 吃不消 | 早期 NIO/Netty → Reactor/WebFlux（编程复杂）→ **Java 21 虚拟线程**（回归同步写法） |
| **GC 停顿** | 堆大了 Full GC 影响延迟 | CMS → G1 → ZGC/Shenandoah（毫秒级停顿） |
| **样板代码多** | 语言偏啰嗦 | Lombok、record、var、Kotlin 的竞争压力 |
| **容器适配差** | 早期 JVM 不识别 cgroup 内存限制 | Java 10+ 容器感知 |
| **配置/版本地狱** | 生态庞大 | Spring Boot Starter/BOM |
| **分布式固有问题** | 语言无关，网络与一致性 | 幂等、重试、补偿、最终一致性设计 |

**Java 的核心优势（为何仍是后端主流）**：
- 强类型 + 成熟 JVM → 大型团队可维护性高
- 生态无可比拟（中间件、监控、工具链）
- JIT 长期运行后的吞吐量极高
- 向后兼容性好，企业级放心

**核心劣势本质**：设计年代是"长时间运行的大进程"，而云原生时代要的是"秒级启动的小进程"，两者理念冲突。近十年 Java 的演进基本都在弥合这个差距。

---

## 四、一张图串起因果

```
单体(SSH)
  │ 并发/数据量增长
  ▼
垂直拆分 + 缓存 + 分库分表 + Dubbo(RPC)         ← 分布式一致性问题出现
  │ 移动端兴起
  ▼
前后端分离 + RESTful + 无状态                    ← 鉴权/跨域问题
  │ 服务数量激增，配置/部署痛苦
  ▼
Spring Boot(简化单服务) + Spring Cloud(治理)    ← 雪崩/事务/运维复杂
  │ Netflix 停更，国内规模化
  ▼
Spring Cloud Alibaba(Nacos/Sentinel/Seata)
  │ 治理能力下沉
  ▼
K8s + Service Mesh + Serverless                  ← Java 启动慢/内存高暴露
  │
  ▼
Java 17/21 + GraalVM + 虚拟线程 + Spring Boot 3  ← 语言层自我救赎
```
