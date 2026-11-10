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
# Create data directory for Railway volume mount
RUN mkdir -p /data
# Create seed directory with default files
RUN mkdir -p /app/seed-data
# Copy seed files from newStrategies to seed-data directory
COPY newStrategies/ /app/seed-data/
# Copy root-level JSON files to seed-data directory
COPY *.json /app/seed-data/
# Copy the built JAR from builder stage using wildcard
COPY --from=builder /app/target/trading-model*.jar app.jar
# Copy startup script
COPY entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh
# Set environment variable for data directory (Railway volume mount point)
ENV DATA_DIR=/data
# Expose port if needed (adjust based on your application)
EXPOSE 8100
# Run the application via entrypoint script
CMD ["/app/entrypoint.sh"]