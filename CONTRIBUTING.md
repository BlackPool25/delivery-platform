# Contributing to Sovereign Delivery Platform

Thank you for your interest in contributing to the **Sovereign Hyper-Local Video & Food Delivery Platform**!

---

## 1. Non-Negotiable Engineering Standards

1. **Zero Shortcuts & Zero TODOs:**
   - Every class, configuration, and entity must be complete, production-grade, and battle-tested.
   - Placeholder comments (`// TODO`, `// FIXME`, `pass`, `NotImplemented`) are prohibited.

2. **Strict Type Safety & Modern Java 21:**
   - Use Java 21 virtual threads (`spring.threads.virtual.enabled: true`).
   - Use immutable records for DTOs.
   - Enforce JPA validation annotations (`@NotNull`, `@Size`, `@DecimalMin`, etc.).

3. **Geodesic Spatial Precision:**
   - Planar Web Mercator (`EPSG:3857`) projections are strictly forbidden for boundaries and dropoff coordinates.
   - All spatial columns must be declared as `geography(Point, 4326)` and `geography(Polygon, 4326)`.
   - Containment checks must use native PostGIS `ST_Covers`.

4. **100% Offline & Sovereign (Zero Cloud SaaS):**
   - No external third-party cloud APIs at runtime (no Twilio, Stripe, Firebase, Cloudflare, etc.).
   - Use the containerized internal boundary services (`local-payment-service`, `local-sms-gateway`, `ntfy`).

5. **Signed-Off Git Commits (`git commit -s`):**
   - All commits must include a Developer Certificate of Origin (`Signed-off-by: Your Name <email>`).
   - Use `git commit -s -m "type(scope): description"`.

---

## 2. Local Development Workflow

### Prerequisites
- JDK 21 (Temurin / GraalVM)
- Docker Engine 26+
- Git

### Build & Run Tests
```bash
# Clone the repository
git clone https://github.com/BlackPool25/delivery-platform.git
cd delivery-platform

# Compile and run test suite with Testcontainers PostGIS
./mvnw clean test

# Package all microservices
./mvnw package -DskipTests
```

### Git Commit Guidelines
```bash
git add .
git commit -s -m "feat(module): descriptive summary of changes"
```
