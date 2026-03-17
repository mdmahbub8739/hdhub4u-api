FROM gradle:8-jdk17 AS build

# Copy source code and set ownership
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Build the fat jar
RUN gradle shadowJar --no-daemon

FROM openjdk:17-slim

# Set working directory
WORKDIR /app
EXPOSE 8080

# Copy the jar from the build stage
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/ktor-app.jar

# Correct ENTRYPOINT syntax
ENTRYPOINT ["java", "-jar", "/app/ktor-app.jar"]
