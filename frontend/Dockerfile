# ---------- build stage ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 构建期注入 API 地址：默认走同源相对路径，由 nginx 反向代理到后端，避免跨域
ARG VITE_API_BASE_URL=/api/ragent
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---------- runtime stage ----------
FROM nginx:1.27-alpine

# 官方镜像启动时会对 /etc/nginx/templates/*.template 做环境变量替换并输出到 /etc/nginx/conf.d/
ENV BACKEND_URL=http://host.docker.internal:9090
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
