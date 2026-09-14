FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common ./common
COPY auth-service ./auth-service
COPY messaging-service ./messaging-service
COPY media-service ./media-service
COPY presence-service ./presence-service
RUN mvn -q -pl common,presence-service -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/presence-service/target/presence-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
