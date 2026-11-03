# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 collector
WORKDIR /app
COPY --from=build /src/target/sensor-data-collector-*-SNAPSHOT.jar /app/collector.jar
USER collector
ENTRYPOINT ["java", "-jar", "/app/collector.jar"]
CMD ["demo"]
