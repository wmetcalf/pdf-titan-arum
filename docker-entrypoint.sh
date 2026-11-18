#!/bin/sh
set -e

: "${DB_URL:?DB_URL is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

# -XX:-UseBASE64Intrinsics: CVE-2026-114938 mitigation (see Dockerfile). HotSpot applies
# _JAVA_OPTIONS AFTER the command line, so when it is set the flag is re-asserted last in it
# (appended verbatim: HotSpot honours quotes there). Unset stays unset.
if [ -n "${_JAVA_OPTIONS+set}" ]; then
  export _JAVA_OPTIONS="${_JAVA_OPTIONS} -XX:-UseBASE64Intrinsics"
fi
exec java -XX:-UseBASE64Intrinsics -jar /app/app.jar server \
  --host 0.0.0.0 \
  --port "${PORT:-7272}" \
  --db "$DB_URL" \
  --db-user "$DB_USER" \
  --db-password "$DB_PASSWORD" \
  --artifact-root /data/artifacts \
  ${WORKERS:+--workers "$WORKERS"} \
  ${TIMEOUT:+--timeout "$TIMEOUT"}
