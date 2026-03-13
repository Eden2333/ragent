#!/bin/sh

# 设置默认后端地址
BACKEND_HOST=${BACKEND_HOST:-backend:9090}

# 替换配置文件中的环境变量
envsubst '${BACKEND_HOST}' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf

# 启动 Nginx
exec nginx -g 'daemon off;'
