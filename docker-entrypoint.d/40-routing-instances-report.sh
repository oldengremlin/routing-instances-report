#!/bin/sh
# Launched by the nginx entrypoint; runs the collector loop in the background.
/usr/local/bin/routing-instances-report.sh &
