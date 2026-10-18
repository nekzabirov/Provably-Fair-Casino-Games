# Builds the example operator from source and runs it on a JRE.
#   docker build -t operator .
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /src/build/install/operator/ /app/

EXPOSE 9090

ENTRYPOINT ["/app/bin/operator"]
