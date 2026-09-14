FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/messaging-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
