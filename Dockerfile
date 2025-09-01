# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder

# Build common library first
WORKDIR /common
COPY ../lemicare-common/pom.xml .
COPY ../lemicare-common/src ./src
RUN mvn clean install -DskipTests

# Then build payment service
WORKDIR /app

# Copy the source code
COPY pom.xml .
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the JAR from the build stage
COPY --from=builder /app/target/lemicare-cms-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8086
ENV SPRING_PROFILES_ACTIVE=cloud

EXPOSE 8086

CMD ["java", "-Dserver.port=8086", "-Dspring.profiles.active=cloud", "-XX:InitialRAMPercentage=50", "-XX:MaxRAMPercentage=70", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
