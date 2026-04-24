FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 8888

ENTRYPOINT ["sh", "-c", "java -Xms512m -Xmx1024m -jar app.jar"]
