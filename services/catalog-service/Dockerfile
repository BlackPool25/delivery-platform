# Multi-Stage Production Dockerfile for catalog-service (Java 21)
# Eclipse Temurin 21 with Layered JAR extraction for minimal container build times

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy wrapper and parent pom
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY services/local-payment-service/pom.xml services/local-payment-service/pom.xml
COPY services/local-sms-gateway/pom.xml services/local-sms-gateway/pom.xml
COPY services/catalog-service/pom.xml services/catalog-service/pom.xml

# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B -pl services/catalog-service -am

# Copy source and compile
COPY services/catalog-service/src services/catalog-service/src
RUN ./mvnw clean package -DskipTests -B -pl services/catalog-service -am

# Extract layered JAR
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/services/catalog-service/target/*.jar extract

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="engineering@delivery.app"
LABEL service="catalog-service"

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/ ./

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
