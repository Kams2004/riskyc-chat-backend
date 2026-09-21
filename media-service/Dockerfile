FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common ./common
COPY auth-service ./auth-service
COPY messaging-service ./messaging-service
COPY media-service ./media-service
COPY presence-service ./presence-service
RUN mvn -q -pl common,media-service -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
# ffmpeg powers MediaController#trimVideo's server-side status-video trim —
# a stream copy (-c copy, no re-encode), so this is the only place in the
# stack that needs it; nothing else here touches video bytes directly.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/media-service/target/media-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
