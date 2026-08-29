# PACC Go 边缘网关镜像（多阶段构建，零第三方依赖）
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
# 复制空占位满足 go build 缓存（可选优化）
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/gateway .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates wget && addgroup -S pacc && adduser -S pacc -G pacc
COPY --from=build /out/gateway /usr/local/bin/gateway
USER pacc
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/gateway"]
