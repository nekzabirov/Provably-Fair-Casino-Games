# Builds the engine from source and runs it on a slim JRE.
#   docker build -t provably-fair-casino-games .
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
COPY resources resources
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /src/build/install/ton-game/ /app/

EXPOSE 8080

ENTRYPOINT ["/app/bin/ton-game"]
