# Multi-stage build for Java application
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
# Copy pom.xml first for better Docker caching
COPY pom.xml .
# Download dependencies
RUN mvn dependency:go-offline
# Copy source code
COPY src ./src
# Build the application
RUN mvn clean package -DskipTests
# List all JAR files created
RUN ls -la target/*.jar
# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
# Copy the built JAR from builder stage using wildcard
COPY --from=builder /app/target/trading-model*.jar app.jar
# Expose port if needed (adjust based on your application)
EXPOSE 8099
# Run the application
CMD ["java", "-jar", "app.jar"]