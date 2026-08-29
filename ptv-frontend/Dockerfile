# PACC 管理后台前端镜像（构建 + Nginx 静态托管，并反代 /api 与 /ws）
# 构建上下文为仓库根目录：docker build -f ptv-frontend/Dockerfile .
FROM node:20-alpine AS build
WORKDIR /workspace
# 国内 npm 镜像加速（构建可覆盖：--build-arg NPM_REGISTRY=...）
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry ${NPM_REGISTRY}
COPY ptv-frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY ptv-frontend ./
RUN npm run build

FROM nginx:1.25-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY ptv-frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
