# Multi-Stage Production Dockerfile for tracking-service (Java 21)
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

# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B -pl services/tracking-service -am

# Copy source and compile
COPY services/tracking-service/src services/tracking-service/src
RUN ./mvnw clean package -DskipTests -B -pl services/tracking-service -am

# Extract layered JAR
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/services/tracking-service/target/*.jar extract

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="engineering@delivery.app"
LABEL service="tracking-service"

RUN apk add --no-cache curl
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/ ./

EXPOSE 8080 9090

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
