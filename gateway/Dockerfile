FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM tomcat:9.0-jdk11-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /build/target/*.war /usr/local/tomcat/webapps/ROOT.war
COPY server.xml /usr/local/tomcat/conf/server.xml
EXPOSE 8080
CMD ["catalina.sh", "run"]
