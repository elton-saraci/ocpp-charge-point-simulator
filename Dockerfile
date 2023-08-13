FROM adoptopenjdk/openjdk11:latest

ARG JAR_FILE=target/*.jar

COPY ./target/charge-point-simulator-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]