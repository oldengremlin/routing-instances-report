# Stage 1: build fat JAR
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Stage 2: nginx + JRE runtime
FROM nginx:latest
LABEL maintainer="Alexander Russkih <olden@ukr-com.net>"

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        default-jre-headless \
        locales locales-all && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/routing-instances-report-1.0.0.jar \
                    /usr/local/bin/routing-instances-report.jar

COPY bin/routing-instances-report.sh        /usr/local/bin/routing-instances-report.sh
COPY docker-entrypoint.d/40-routing-instances-report.sh \
                                            /docker-entrypoint.d/40-routing-instances-report.sh

RUN chmod +x /usr/local/bin/routing-instances-report.sh \
             /docker-entrypoint.d/40-routing-instances-report.sh

ENV LANG=uk_UA.UTF-8
ENV TZ=Europe/Kiev

# Environment variables for the report collector (supply at runtime):
# ROUTER_USER, ROUTER_PASS, CISCO_ENABLE
# JUNIPER_HOSTS, CISCO_HOSTS, ROUTEROS_HOSTS
# REPORT_PATH (default: /usr/share/nginx/html/index.html)

EXPOSE 80
