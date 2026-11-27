# Builds one service module: docker build --build-arg MODULE=trading-service .
FROM gradle:9.2.1-jdk21 AS build
ARG MODULE
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY common ./common
COPY ${MODULE} ./${MODULE}
# settings.gradle.kts lists every module; give the ones not copied an empty folder.
RUN for m in gateway identity-service trading-service news-service realtime-service lake-sink analytics-service; do mkdir -p $m; done
RUN --mount=type=cache,target=/home/gradle/.gradle gradle --no-daemon -q :${MODULE}:bootJar -x test \
 && cp ${MODULE}/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
ARG OTEL_AGENT_VERSION=2.34.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app && chmod 644 /otel/opentelemetry-javaagent.jar
COPY --from=build /app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-javaagent:/otel/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
