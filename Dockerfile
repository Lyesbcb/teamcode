FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/teamcode
COPY --chown=10001:10001 app/build/libs/app-all.jar /opt/teamcode/teamcode.jar
USER 10001:10001
ENV TEAMCODE_HOST=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/teamcode/teamcode.jar"]
