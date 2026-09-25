# PACC PTV 后端镜像（多阶段构建 + 华为云 Maven 镜像加速）
# 构建上下文为仓库根目录：docker build -f ptv-backend/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# 使用华为云镜像加速依赖下载
COPY deploy/docker/maven-settings.xml /m2/settings.xml
COPY ptv-backend/pom.xml .
RUN mvn -s /m2/settings.xml dependency:go-offline -B
COPY ptv-backend/src ./src
RUN mvn -s /m2/settings.xml clean package -DskipTests -B

# 运行时（Java 21 JRE + curl 用于健康检查）
FROM eclipse-temurin:21-jre-jammy
# fonts-dejavu-core：回放逐帧预览要用 Java2D 烧操作者水印，镜像里没有任何字体时 drawString 会画不出字
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0"
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
