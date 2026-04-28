#!/bin/sh
# Runs the report collector once per day in a loop.
while true; do
    date
    /opt/java/openjdk/bin/java -jar /usr/local/bin/routing-instances-report.jar
    sleep 86400
done
