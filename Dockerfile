# Stage 1: build fat JAR
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Stage 2: slim JRE layer (copy from Temurin image)
FROM eclipse-temurin:21-jre-noble AS jre-provider

# Stage 3: nginx + JRE runtime
FROM nginx:mainline
LABEL maintainer="Alexander Russkih <olden@ukr-com.net>"

RUN apt-get update && \
    apt-get install -y --no-install-recommends locales locales-all && \
    rm -rf /var/lib/apt/lists/*

COPY --from=jre-provider /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

COPY --from=builder /build/target/routing-instances-report-1.0.jar \
                    /usr/local/bin/routing-instances-report.jar

COPY bin/routing-instances-report.sh        /usr/local/bin/routing-instances-report.sh
COPY docker-entrypoint.d/40-routing-instances-report.sh \
                                            /docker-entrypoint.d/40-routing-instances-report.sh

RUN chmod +x /usr/local/bin/routing-instances-report.sh \
             /docker-entrypoint.d/40-routing-instances-report.sh

ENV LANG=uk_UA.UTF-8
ENV TZ=Europe/Kiev

# Required at runtime — supply via -e or docker-compose environment:
#   ROUTER_USER, ROUTER_PASS
#   CISCO_ENABLE        (if CISCO_HOSTS is non-empty)
#   JUNIPER_HOSTS       (comma-separated)
#   CISCO_HOSTS         (comma-separated)
#   ROUTEROS_HOSTS      (comma-separated)
#   REPORT_PATH         (default: /usr/share/nginx/html/index.html)
#   LOG_LEVEL           (default: info)

EXPOSE 80
