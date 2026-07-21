FROM gradle:jdk21-corretto-al2023 AS build
COPY . /build/
WORKDIR /build
RUN ./gradlew buildFatJar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S sluice && adduser -S sluice -G sluice
WORKDIR /app
COPY --from=build /build/build/libs/sluice-all.jar /app/sluice.jar
USER sluice
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q --spider http://localhost:8080/health/live || exit 1
ENTRYPOINT ["java", "-jar", "/app/sluice.jar", "-config=/app/config/application.yaml"]
