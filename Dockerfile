FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /project
COPY pom.xml .
COPY src src
COPY scripts scripts
RUN mvn -B -DskipTests package

FROM build AS tests
ENTRYPOINT ["bash", "/project/scripts/container-tests.sh"]

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S planner && adduser -S planner -G planner
COPY --from=build /project/target/event-planner.jar /app/app.jar
USER planner
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
