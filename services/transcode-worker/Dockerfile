# Multi-Stage Production Dockerfile for transcode-worker (Java 21)
# Utilizes Alpine Linux with Mesa Gallium VAAPI drivers for AMD Radeon RX 7900 GRE hardware acceleration

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy wrapper and parent pom
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY services/local-payment-service/pom.xml services/local-payment-service/pom.xml
COPY services/local-sms-gateway/pom.xml services/local-sms-gateway/pom.xml
COPY services/auth-service/pom.xml services/auth-service/pom.xml
COPY services/catalog-service/pom.xml services/catalog-service/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/tracking-service/pom.xml services/tracking-service/pom.xml
COPY services/media-service/pom.xml services/media-service/pom.xml
COPY services/transcode-worker/pom.xml services/transcode-worker/pom.xml

# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B -pl services/transcode-worker -am

# Copy source and compile
COPY services/transcode-worker/src services/transcode-worker/src
RUN ./mvnw clean package -DskipTests -B -pl services/transcode-worker -am

# Extract layered JAR
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/services/transcode-worker/target/*.jar extract

# Runtime Stage with AMD Mesa VAAPI hardware acceleration & FFmpeg
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="engineering@delivery.app"
LABEL service="transcode-worker"
LABEL hardware="AMD Radeon RX 7900 GRE"

# Install FFmpeg, Mesa VAAPI gallium drivers, and utilities
RUN apk add --no-cache ffmpeg mesa-va-gallium libva-utils bash curl

# Ensure video (GID 44) and render (GID 107) groups exist for host DRM access
RUN addgroup -g 44 -S video 2>/dev/null || true && \
    addgroup -g 107 -S render 2>/dev/null || true && \
    addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring && \
    addgroup spring video && \
    addgroup spring render

USER spring:spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/ ./

EXPOSE 8080

# Environment variables for AMD RX 7900 GRE (Navi 31 XL / gfx1100)
ENV LIBVA_DRIVER_NAME="radeonsi" \
    HSA_OVERRIDE_GFX_VERSION="11.0.0" \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
