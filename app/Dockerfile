FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY build/libs/app-all.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
