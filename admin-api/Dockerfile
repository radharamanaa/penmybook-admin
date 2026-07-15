# THROWAWAY — used only by tests/integration/docker-compose.yml.
# Builds the Spring Boot fat jar inside the image, then runs it on a slim JRE.
# Delete this file together with tests/integration/ once the cross-service
# integration suite is retired (see tests/integration/README.md §Discard).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/*-SNAPSHOT.jar /tmp/app.jar 2>/dev/null \
    || cp $(ls target/*.jar | grep -v '\.original$' | head -1) /tmp/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
