# Build context is the repository root.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --console=plain :synthetic-web:installDist

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/synthetic-web/build/install/synthetic-web /app
USER 65534
EXPOSE 80
ENTRYPOINT ["/app/bin/synthetic-web"]
