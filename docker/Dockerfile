FROM eclipse-temurin:21.0.9_10-jre-jammy
WORKDIR /app
COPY demo-services/build/libs/demo-services.jar /app/demo-services.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/demo-services.jar"]
