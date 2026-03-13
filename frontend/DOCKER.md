# Frontend Docker 部署指南

## 快速开始

### 1. 构建镜像

```bash
docker build -t ragent-frontend:latest .
```

### 2. 运行容器

```bash
# 独立运行（不连接后端）
docker run -d -p 3000:80 --name ragent-frontend ragent-frontend:latest

# 连接后端服务
docker run -d -p 3000:80 \
  -e BACKEND_HOST=backend:9090 \
  --name ragent-frontend \
  ragent-frontend:latest
```

### 3. 使用 Docker Compose

```bash
# 启动
docker-compose up -d

# 停止
docker-compose down

# 查看日志
docker-compose logs -f
```

## 访问应用

浏览器访问：http://localhost:3000

## 配置说明

### 后端 API 地址

通过环境变量 `BACKEND_HOST` 配置：

```bash
# 使用 Docker 运行
docker run -d -p 3000:80 \
  -e BACKEND_HOST=192.168.1.100:9090 \
  ragent-frontend:latest

# 使用 Docker Compose
# 编辑 docker-compose.yml
environment:
  - BACKEND_HOST=192.168.1.100:9090
```

### 端口映射

默认映射到主机 3000 端口，可修改：

```bash
docker run -d -p 8080:80 ragent-frontend:latest
```

## 生产环境部署

### 完整示例

```bash
docker run -d \
  --name ragent-frontend \
  -p 3000:80 \
  -e BACKEND_HOST=api.example.com:443 \
  --restart unless-stopped \
  ragent-frontend:latest
```

### 健康检查

```bash
docker ps  # 查看容器状态
curl http://localhost:3000  # 测试访问
```

## 故障排查

### 查看容器日志

```bash
docker logs ragent-frontend
```

### 进入容器调试

```bash
docker exec -it ragent-frontend sh
```

### 重启容器

```bash
docker restart ragent-frontend
```

### 检查 Nginx 配置

```bash
docker exec ragent-frontend cat /etc/nginx/conf.d/default.conf
```
