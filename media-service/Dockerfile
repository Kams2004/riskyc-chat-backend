FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/media-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
