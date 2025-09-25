# Multistage: build with Maven, then run on slim JRE.
# Stage 1 — build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# pre-fetch deps for faster rebuilds
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Stage 2 — runtime
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Kolkata
# non-root user
RUN useradd -ms /bin/bash appuser
USER appuser
WORKDIR /app
COPY --from=build /workspace/target/harborwatch-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
