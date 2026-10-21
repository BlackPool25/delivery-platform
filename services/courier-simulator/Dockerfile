# Multi-Stage Production Dockerfile for courier-simulator (Java 21)
# Eclipse Temurin 21 with Layered JAR extraction for minimal container build times

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
COPY services/courier-simulator/pom.xml services/courier-simulator/pom.xml

# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B -pl services/courier-simulator -am

# Copy source and compile
COPY services/courier-simulator/src services/courier-simulator/src
RUN ./mvnw clean package -DskipTests -B -pl services/courier-simulator -am

# Extract layered JAR
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/services/courier-simulator/target/*.jar extract

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="engineering@delivery.app"
LABEL service="courier-simulator"

RUN apk add --no-cache curl
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/ ./

EXPOSE 8080

# Tuned for strict 128Mi-256Mi container memory budget
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Xss256k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
