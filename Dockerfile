# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Cache Maven dependencies by resolving them before copying sources
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy sources and build the jar
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as a non-root user (best practice)
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /workspace/target/*.jar /app/app.jar
USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]