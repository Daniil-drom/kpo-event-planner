#!/usr/bin/env bash
set -u
mkdir -p /reports
rm -rf /reports/surefire-reports /reports/failsafe-reports
mvn -B verify
result=$?
mkdir -p /reports
for kind in surefire-reports failsafe-reports; do
  if [ -d "target/$kind" ]; then
    cp -R "target/$kind" /reports/ || result=1
  fi
done
exit "$result"
